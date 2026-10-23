package main

import (
	"crypto/rand"
	"database/sql"
	_ "embed"
	"errors"
	"fmt"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

//go:embed schema.sql
var schemaSQL string

// nowMs 当前时间（epoch 毫秒 UTC）。
func nowMs() int64 {
	return time.Now().UnixMilli()
}

// openDB 打开 SQLite 连接并执行幂等建表迁移。
//
// SQLite 采用单写者模型，这里将连接池收敛为单连接以彻底规避 "database is locked"。
func openDB(path string) (*sql.DB, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, fmt.Errorf("打开数据库失败: %w", err)
	}
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)
	if _, err := db.Exec("PRAGMA busy_timeout = 5000;"); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("设置 busy_timeout 失败: %w", err)
	}
	if err := initSchema(db); err != nil {
		_ = db.Close()
		return nil, err
	}
	return db, nil
}

// splitSQL 将脚本按分号切分为独立语句，并剥离 "--" 行注释。
// schema.sql 中不存在字符串字面量内含 "--" 或 ";" 的情况，故该切分是安全的。
func splitSQL(script string) []string {
	var sb strings.Builder
	for _, line := range strings.Split(script, "\n") {
		if idx := strings.Index(line, "--"); idx >= 0 {
			line = line[:idx]
		}
		sb.WriteString(line)
		sb.WriteByte('\n')
	}
	var stmts []string
	for _, part := range strings.Split(sb.String(), ";") {
		s := strings.TrimSpace(part)
		if s != "" {
			stmts = append(stmts, s)
		}
	}
	return stmts
}

// initSchema 幂等执行建表脚本（全部使用 IF NOT EXISTS，可重复执行），
// 并对**已存在**的旧库做增量迁移（CREATE TABLE IF NOT EXISTS 不会给旧表补列）。
func initSchema(db *sql.DB) error {
	for _, stmt := range splitSQL(schemaSQL) {
		if _, err := db.Exec(stmt); err != nil {
			return fmt.Errorf("执行建表语句失败 [%s]: %w", firstLine(stmt), err)
		}
	}
	// 批次7 三级权限：旧库补 license_code.tier（存量码 DEFAULT 3 = 高级会员，不伤害现有用户）。
	// ensureColumn 幂等：列已存在（新库/已迁移库）直接跳过。
	if err := ensureColumn(db, "license_code", "tier", "tier INTEGER NOT NULL DEFAULT 3"); err != nil {
		return fmt.Errorf("迁移 license_code.tier 失败: %w", err)
	}
	return nil
}

// ensureColumn 幂等补列（SQLite 旧库迁移）：通过 PRAGMA table_info 判断列是否存在，
// 不存在才执行 `ALTER TABLE <table> ADD COLUMN <addColumnDDL>`。
//
// 说明：SQLite 的 ADD COLUMN 不能添加带非常量默认值/NOT NULL 无默认值的列；这里的
// tier 是常量 DEFAULT 3，语法合法且对存量行回填 3（= 三级权限里的高级会员，兼容旧用户）。
func ensureColumn(db *sql.DB, table, column, addColumnDDL string) error {
	rows, err := db.Query(`PRAGMA table_info(` + table + `)`)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var (
			cid       int
			name      string
			ctype     string
			notNull   int
			dfltValue sql.NullString
			pk        int
		)
		if err := rows.Scan(&cid, &name, &ctype, &notNull, &dfltValue, &pk); err != nil {
			return err
		}
		if name == column {
			return nil // 列已存在：幂等跳过
		}
	}
	if err := rows.Err(); err != nil {
		return err
	}
	_, err = db.Exec(`ALTER TABLE ` + table + ` ADD COLUMN ` + addColumnDDL)
	return err
}

// firstLine 返回语句首行，用于错误提示。
func firstLine(s string) string {
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		return s[:i]
	}
	return s
}

// ---------------------------------------------------------------------------
// license_code 相关查询
// ---------------------------------------------------------------------------

const codeColumns = `id, code, code_hash, type, tier, duration_days, status, remark, created_at,
	activated_at, expire_at, revoked_at, revoke_reason, created_by`

// scanCode 从一行扫描出 LicenseCode。
func scanCode(sc interface{ Scan(...interface{}) error }) (*LicenseCode, error) {
	var c LicenseCode
	err := sc.Scan(&c.ID, &c.Code, &c.CodeHash, &c.Type, &c.Tier, &c.DurationDays, &c.Status, &c.Remark,
		&c.CreatedAt, &c.ActivatedAt, &c.ExpireAt, &c.RevokedAt, &c.RevokeReason, &c.CreatedBy)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &c, nil
}

// getCodeByCode 按展示码查询，未找到返回 (nil, nil)。
func getCodeByCode(db *sql.DB, code string) (*LicenseCode, error) {
	row := db.QueryRow(`SELECT `+codeColumns+` FROM license_code WHERE code = ?`, code)
	return scanCode(row)
}

// getCodeByID 按主键查询，未找到返回 (nil, nil)。
func getCodeByID(db *sql.DB, id int64) (*LicenseCode, error) {
	row := db.QueryRow(`SELECT `+codeColumns+` FROM license_code WHERE id = ?`, id)
	return scanCode(row)
}

// insertCode 插入一条新兑换码（等级取默认 TierPremium=3，存量兼容）。
// 返回自增主键。需要显式等级的新码请用 insertCodeWithTier。
func insertCode(db *sql.DB, code, typ string, durationDays int64, remark, createdBy string, now int64) (int64, error) {
	return insertCodeWithTier(db, code, typ, TierDefault, durationDays, remark, createdBy, now)
}

// insertCodeWithTier 插入一条指定等级（1/2/3）的新兑换码，返回自增主键。
// tier 非法（0/负/超界）由 DB CHECK 拒绝——CLI 层已用 parseTier 校验并给出可读错误。
func insertCodeWithTier(db *sql.DB, code, typ string, tier int, durationDays int64,
	remark, createdBy string, now int64) (int64, error) {
	res, err := db.Exec(`INSERT INTO license_code
		(code, code_hash, type, tier, duration_days, status, remark, created_at, created_by)
		VALUES (?, ?, ?, ?, ?, 'active', ?, ?, ?)`,
		code, codeHash(code), typ, normalizeTier(tier), durationDays, remark, now, createdBy)
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

// setCodeActivated 首次激活时写入 activated_at 与 expire_at（expireAtValid=false 表示永久）。
func setCodeActivated(db *sql.DB, codeID, activatedAt int64, expireAt int64, expireAtValid bool) error {
	if expireAtValid {
		_, err := db.Exec(`UPDATE license_code SET activated_at = ?, expire_at = ? WHERE id = ?`,
			activatedAt, expireAt, codeID)
		return err
	}
	_, err := db.Exec(`UPDATE license_code SET activated_at = ?, expire_at = NULL WHERE id = ?`,
		activatedAt, codeID)
	return err
}

// revokeCodeByID 吊销指定码（幂等）。
func revokeCodeByID(db *sql.DB, codeID int64, reason string, now int64) error {
	_, err := db.Exec(`UPDATE license_code
		SET status = 'revoked', revoked_at = ?, revoke_reason = ?
		WHERE id = ? AND status <> 'revoked'`, now, reason, codeID)
	return err
}

// ---------------------------------------------------------------------------
// device_binding 相关查询
// ---------------------------------------------------------------------------

const bindingColumns = `id, code_id, device_hash, device_label, status, bound_at,
	last_seen_at, last_verify_at, verify_count`

// scanBinding 扫描一行 DeviceBinding。
func scanBinding(sc interface{ Scan(...interface{}) error }) (*DeviceBinding, error) {
	var b DeviceBinding
	err := sc.Scan(&b.ID, &b.CodeID, &b.DeviceHash, &b.DeviceLabel, &b.Status, &b.BoundAt,
		&b.LastSeenAt, &b.LastVerifyAt, &b.VerifyCount)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &b, nil
}

// getActiveBinding 查询某码当前生效的绑定（一码一机，最多一条），无则返回 (nil, nil)。
func getActiveBinding(db *sql.DB, codeID int64) (*DeviceBinding, error) {
	row := db.QueryRow(`SELECT `+bindingColumns+`
		FROM device_binding WHERE code_id = ? AND status = 'active' LIMIT 1`, codeID)
	return scanBinding(row)
}

// upsertBinding 写入 / 复活一条设备绑定。
//
// 冲突键为 (code_id, device_hash)：若同设备行已存在则重新置为 active；
// device_label 仅在本次提供了非空值时覆盖，避免换绑时把原标签清空。
func upsertBinding(db *sql.DB, codeID int64, deviceHash, deviceLabel string, now int64) error {
	_, err := db.Exec(`INSERT INTO device_binding
		(code_id, device_hash, device_label, status, bound_at)
		VALUES (?, ?, ?, 'active', ?)
		ON CONFLICT(code_id, device_hash) DO UPDATE SET
			status = 'active',
			bound_at = excluded.bound_at,
			device_label = CASE
				WHEN excluded.device_label <> '' THEN excluded.device_label
				ELSE device_binding.device_label END`,
		codeID, deviceHash, deviceLabel, now)
	return err
}

// markBindingUnbound 将某条绑定置为 unbound（换绑 / 解绑用）。
func markBindingUnbound(db *sql.DB, bindingID int64) error {
	_, err := db.Exec(`UPDATE device_binding SET status = 'unbound' WHERE id = ?`, bindingID)
	return err
}

// bindState 是 bindDeviceAtomic 的裁决结果。
type bindState int

const (
	bindFirst bindState = iota // 本次完成首次绑定（拿到首绑权）
	bindSame                   // 已存在同机 active 绑定（幂等成功）
	bindOther                  // 已绑定其他设备（拒绝）
)

// bindDeviceAtomic 原子完成"一码一机"判定与首绑写入。
//
// 修复的竞态（P0）：原实现"读绑定 → 判定 → 写绑定"三步之间无互斥，两台设备并发
// 激活同一码可交错通过判定、双双绑定成功。本函数用两层防护：
//  1. 条件更新抢首绑权：UPDATE ... WHERE activated_at IS NULL，靠影响行数裁决，
//     数据库层保证只有一个并发者能置位；
//  2. 事务包裹两步写（置 activated_at + 写绑定），中间失败整体回滚，消除半激活状态。
//
// 互斥性说明：openDB 将连接池收敛为单连接（MaxOpenConns=1），事务从 Begin 到
// Commit 独占该连接，事务体天然串行；条件更新则在将来放宽连接池时仍成立。
func bindDeviceAtomic(db *sql.DB, codeID int64, deviceHash, deviceLabel string,
	now int64, expireAt int64, expireValid bool) (bindState, error) {

	// 快速路径：已有 active 绑定时纯读判定，无需开事务。
	active, err := getActiveBinding(db, codeID)
	if err != nil {
		return bindFirst, err
	}
	if active != nil {
		if active.DeviceHash == deviceHash {
			return bindSame, nil
		}
		return bindOther, nil
	}

	// 竞态窗口：并发首绑在此交错，以下事务 + 条件更新做最终裁决。
	tx, err := db.Begin()
	if err != nil {
		return bindFirst, err
	}
	defer tx.Rollback()

	var res sql.Result
	if expireValid {
		res, err = tx.Exec(`UPDATE license_code SET activated_at = ?, expire_at = ?
			WHERE id = ? AND activated_at IS NULL`, now, expireAt, codeID)
	} else {
		res, err = tx.Exec(`UPDATE license_code SET activated_at = ?, expire_at = NULL
			WHERE id = ? AND activated_at IS NULL`, now, codeID)
	}
	if err != nil {
		return bindFirst, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return bindFirst, err
	}
	if n == 0 {
		// 首绑权被并发者抢走：重查绑定，裁决同机（幂等）/ 他机（拒绝）。
		var dh string
		qErr := tx.QueryRow(`SELECT device_hash FROM device_binding
			WHERE code_id = ? AND status = 'active' LIMIT 1`, codeID).Scan(&dh)
		if errors.Is(qErr, sql.ErrNoRows) {
			// 抢先者已置 activated_at 但绑定行尚未可见（防御分支：单连接池下不会
			// 发生，将来改连接池时由事务隔离保证）。按他机拒绝，客户端可重试。
			return bindOther, nil
		}
		if qErr != nil {
			return bindFirst, qErr
		}
		if dh == deviceHash {
			return bindSame, nil
		}
		return bindOther, nil
	}

	if _, err := tx.Exec(`INSERT INTO device_binding
		(code_id, device_hash, device_label, status, bound_at)
		VALUES (?, ?, ?, 'active', ?)
		ON CONFLICT(code_id, device_hash) DO UPDATE SET
			status = 'active',
			bound_at = excluded.bound_at,
			device_label = CASE
				WHEN excluded.device_label <> '' THEN excluded.device_label
				ELSE device_binding.device_label END`,
		codeID, deviceHash, deviceLabel, now); err != nil {
		return bindFirst, err
	}
	if err := tx.Commit(); err != nil {
		return bindFirst, err
	}
	return bindFirst, nil
}

// touchBinding 记录一次成功校验：更新 last_seen_at / last_verify_at / 计数自增。
func touchBinding(db *sql.DB, bindingID, now int64) error {
	_, err := db.Exec(`UPDATE device_binding
		SET last_seen_at = ?, last_verify_at = ?, verify_count = verify_count + 1
		WHERE id = ?`, now, now, bindingID)
	return err
}

// countRebinds 统计某码历史换绑次数（以流水为准）。
func countRebinds(db *sql.DB, codeID int64) (int64, error) {
	var n int64
	// 只统计**成功**的换绑（result_code=0）：被拒绝的尝试（超限/禁用）不占额度，
	// 否则"先被默认策略拒过一次"的码在放开限额后仍会被误判超限。
	err := db.QueryRow(`SELECT COUNT(*) FROM verify_log
		WHERE code_id = ? AND action = 'rebind' AND result_code = 0`, codeID).Scan(&n)
	return n, err
}

// ---------------------------------------------------------------------------
// verify_log / used_nonce
// ---------------------------------------------------------------------------

// insertLog 写入一条审计流水（resultCode=0 表示成功）。
func insertLog(db *sql.DB, codeID *int64, deviceHash, action string, resultCode int, ip, ua string, now int64) {
	_, err := db.Exec(`INSERT INTO verify_log
		(code_id, device_hash, action, result_code, ip, ua, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)`,
		nullableInt64(codeID), deviceHash, action, resultCode, ip, ua, now)
	if err != nil {
		// 流水写入失败不影响主流程，仅记录。
		logWarn("写入 verify_log 失败: %v", err)
	}
}

// consumeNonce 原子占用一个 nonce。返回 (true, nil) 表示首次使用；
// 返回 (false, nil) 表示已存在（重放）。
func consumeNonce(db *sql.DB, nonce string, now int64) (bool, error) {
	res, err := db.Exec(`INSERT OR IGNORE INTO used_nonce (nonce, used_at) VALUES (?, ?)`, nonce, now)
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return false, err
	}
	return n > 0, nil
}

// cleanupNonces 删除 used_at 早于 before 的 nonce 记录，返回删除条数。
func cleanupNonces(db *sql.DB, before int64) (int64, error) {
	res, err := db.Exec(`DELETE FROM used_nonce WHERE used_at < ?`, before)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

// nullableInt64 将 *int64 转换为可写入 SQL 的值。
func nullableInt64(p *int64) interface{} {
	if p == nil {
		return nil
	}
	return *p
}

// codeIDPtr 便捷构造 *int64。
func codeIDPtr(v int64) *int64 {
	return &v
}

// newUUID 生成 RFC 4122 版本 4 的 UUID 字符串。
func newUUID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	b[6] = (b[6] & 0x0f) | 0x40 // version 4
	b[8] = (b[8] & 0x3f) | 0x80 // variant 10
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
