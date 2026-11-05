package main

import (
	"database/sql"
	"fmt"
	"strings"
)

// ---------------------------------------------------------------------------
// 管理后台数据查询（2026-11-06）
//
// 与授权接口的数据读取分开：这里只服务 /admin/api/*，所有 SQL 全参数化；
// 列表/统计口径以需求文档 §4.3–§4.6 为准。
// ---------------------------------------------------------------------------

// CodeFilter 兑换码列表筛选参数。
type CodeFilter struct {
	Status  string // active | revoked | 空=全部
	Usage   string // unused | active | expired | revoked | exhausted | 空=全部
	Tier    string // "1" | "2" | "3" | "gm" | 空=全部
	Type    string // month | permanent | 空=全部
	Keyword string // code / remark / device_label
	From    int64  // 创建时间起（epoch ms，0=不限）
	To      int64  // 创建时间止（epoch ms，0=不限）
	Page    int
	Size    int
	Sort    string // created_desc | expire_asc
}

// CodeListItem 兑换码列表/详情的基本信息。
type CodeListItem struct {
	ID          int64  `json:"id"`
	Code        string `json:"code"`
	Type        string `json:"type"`
	Tier        int64  `json:"tier"`
	Role        int    `json:"role"`
	IsGM        bool   `json:"isGm"`
	Status      string `json:"status"`
	Usage       string `json:"usage"`
	BoundDevice string `json:"boundDevice"`
	ActivatedAt int64  `json:"activatedAt"` // 0=未激活
	ExpireAt    int64  `json:"expireAt"`    // 0=永久/未激活
	RemainDays  int64  `json:"remainDays"`  // -1=永久；-2=未使用；其余=剩余天数
	Remark      string `json:"remark"`
	CreatedAt   int64  `json:"createdAt"`
	CreatedBy   string `json:"createdBy"`
}

// LogItem 一条使用记录。
type LogItem struct {
	ID          int64  `json:"id"`
	CreatedAt   int64  `json:"createdAt"`
	Action      string `json:"action"`
	ResultCode  int    `json:"resultCode"`
	ResultText  string `json:"resultText"`
	DeviceHash  string `json:"deviceHash"`
	DeviceLabel string `json:"deviceLabel"`
	IP          string `json:"ip"`
	UA          string `json:"ua"`
}

// Overview 概览统计。
type Overview struct {
	Total       int64            `json:"total"`
	Unused      int64            `json:"unused"`
	Active      int64            `json:"active"`
	Expired     int64            `json:"expired"`
	Revoked     int64            `json:"revoked"`
	New7d       int64            `json:"new7d"`
	Activated7d int64            `json:"activated7d"`
	ByTier      map[string]int64 `json:"byTier"`
	ByType      map[string]int64 `json:"byType"`
	RecentAudit []AdminAuditItem `json:"recentAudit"`
}

// AdminAuditItem 审计记录。
type AdminAuditItem struct {
	ID         int64  `json:"id"`
	CreatedAt  int64  `json:"createdAt"`
	Actor      string `json:"actor"`
	Action     string `json:"action"`
	TargetType string `json:"targetType"`
	TargetID   string `json:"targetId"`
	Detail     string `json:"detail"`
	IP         string `json:"ip"`
}

// usageSQL 返回某使用状态的 SQL 条件（调用方按序追加 now 参数）。
func usageSQL(usage string) (string, []interface{}) {
	switch usage {
	case "unused":
		return "c.status = 'active' AND c.activated_at IS NULL", nil
	case "active":
		return "c.status = 'active' AND c.activated_at IS NOT NULL AND (c.type = 'permanent' OR c.expire_at IS NULL OR c.expire_at > ?)", []interface{}{nowMs()}
	case "expired":
		return "c.status = 'active' AND c.type = 'month' AND c.expire_at IS NOT NULL AND c.expire_at <= ?", []interface{}{nowMs()}
	case "revoked":
		return "c.status = 'revoked'", nil
	case "exhausted":
		return "c.status = 'exhausted'", nil
	default:
		return "", nil
	}
}

// buildCodeWhere 组装列表 WHERE（同时用于 count 与分页查询）。
func buildCodeWhere(f CodeFilter) (string, []interface{}) {
	var conds []string
	var args []interface{}
	if f.Status != "" {
		conds = append(conds, "c.status = ?")
		args = append(args, f.Status)
	}
	if f.Usage != "" {
		s, a := usageSQL(f.Usage)
		if s != "" {
			conds = append(conds, s)
			args = append(args, a...)
		}
	}
	if f.Tier != "" {
		if f.Tier == "gm" {
			conds = append(conds, "c.is_gm = 1")
		} else {
			conds = append(conds, "c.is_gm = 0 AND c.tier = ?")
			args = append(args, f.Tier)
		}
	}
	if f.Type != "" {
		conds = append(conds, "c.type = ?")
		args = append(args, f.Type)
	}
	if f.Keyword != "" {
		like := "%" + f.Keyword + "%"
		conds = append(conds, "(c.code LIKE ? OR c.remark LIKE ? OR b.device_label LIKE ?)")
		args = append(args, like, like, like)
	}
	if f.From > 0 {
		conds = append(conds, "c.created_at >= ?")
		args = append(args, f.From)
	}
	if f.To > 0 {
		conds = append(conds, "c.created_at <= ?")
		args = append(args, f.To)
	}
	if len(conds) == 0 {
		return "", nil
	}
	return " WHERE " + strings.Join(conds, " AND "), args
}

// usageOf 计算展示用使用状态（需求 §4.5 优先级）。
func usageOf(status string, typ string, activatedAt, expireAt sql.NullInt64, now int64) string {
	switch status {
	case StatusRevoked:
		return "revoked"
	case StatusExhausted:
		return "exhausted"
	}
	if !activatedAt.Valid {
		return "unused"
	}
	if typ == TypeMonth && expireAt.Valid && expireAt.Int64 <= now {
		return "expired"
	}
	return "active"
}

// remainDaysOf 剩余天数：-1=永久；-2=未使用；其余向上取整、最小 0。
func remainDaysOf(typ string, activatedAt, expireAt sql.NullInt64, now int64) int64 {
	if !activatedAt.Valid {
		return -2
	}
	if typ == TypePermanent {
		return -1
	}
	if !expireAt.Valid {
		return -1
	}
	if expireAt.Int64 <= now {
		return 0
	}
	return (expireAt.Int64 - now + MsPerDay - 1) / MsPerDay
}

// listCodes 兑换码分页列表。
func listCodes(db *sql.DB, f CodeFilter) ([]CodeListItem, int64, error) {
	where, args := buildCodeWhere(f)
	base := ` FROM license_code c
		LEFT JOIN device_binding b ON b.code_id = c.id AND b.status = 'active'` + where

	var total int64
	if err := db.QueryRow(`SELECT COUNT(*)`+base, args...).Scan(&total); err != nil {
		return nil, 0, err
	}

	order := " ORDER BY c.created_at DESC, c.id DESC"
	if f.Sort == "expire_asc" {
		order = " ORDER BY (c.expire_at IS NULL), c.expire_at ASC, c.id DESC"
	}
	page, size := normalizePage(f.Page, f.Size)
	q := `SELECT c.id, c.code, c.type, c.tier, c.is_gm, c.status, c.remark, c.created_at,
		c.activated_at, c.expire_at, COALESCE(b.device_label, ''), c.created_by` + base + order + ` LIMIT ? OFFSET ?`
	rows, err := db.Query(q, append(args, size, (page-1)*size)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	now := nowMs()
	items := make([]CodeListItem, 0, size)
	for rows.Next() {
		var (
			it          CodeListItem
			activatedAt sql.NullInt64
			expireAt    sql.NullInt64
		)
		var isGM int
		if err := rows.Scan(&it.ID, &it.Code, &it.Type, &it.Tier, &isGM, &it.Status, &it.Remark,
			&it.CreatedAt, &activatedAt, &expireAt, &it.BoundDevice, &it.CreatedBy); err != nil {
			return nil, 0, err
		}
		it.IsGM = isGM != 0
		it.Role = 1
		if it.IsGM {
			it.Role = RoleGM
		} else if it.Tier >= 1 && it.Tier <= 3 {
			it.Role = int(it.Tier)
		}
		it.Usage = usageOf(it.Status, it.Type, activatedAt, expireAt, now)
		if activatedAt.Valid {
			it.ActivatedAt = activatedAt.Int64
		}
		if expireAt.Valid {
			it.ExpireAt = expireAt.Int64
		}
		it.RemainDays = remainDaysOf(it.Type, activatedAt, expireAt, now)
		items = append(items, it)
	}
	return items, total, rows.Err()
}

// getCodeDetail 单码详情 + 当前绑定 + 累计 verify 次数。
func getCodeDetail(db *sql.DB, id int64) (*CodeListItem, []DeviceBinding, int64, error) {
	c, err := getCodeByID(db, id)
	if err != nil {
		return nil, nil, 0, err
	}
	if c == nil {
		return nil, nil, 0, nil
	}
	now := nowMs()
	it := &CodeListItem{
		ID:          c.ID,
		Code:        c.Code,
		Type:        c.Type,
		Tier:        c.Tier,
		Role:        roleOfCode(c),
		IsGM:        c.IsGM,
		Status:      c.Status,
		Remark:      c.Remark,
		CreatedAt:   c.CreatedAt,
		CreatedBy:   c.CreatedBy,
		ActivatedAt: nullInt64OrZero(c.ActivatedAt),
		ExpireAt:    nullInt64OrZero(c.ExpireAt),
	}
	it.Usage = usageOf(c.Status, c.Type, c.ActivatedAt, c.ExpireAt, now)
	it.RemainDays = remainDaysOf(c.Type, c.ActivatedAt, c.ExpireAt, now)

	rows, err := db.Query(`SELECT `+bindingColumns+` FROM device_binding
		WHERE code_id = ? ORDER BY (status = 'active') DESC, bound_at DESC LIMIT 20`, id)
	if err != nil {
		return nil, nil, 0, err
	}
	defer rows.Close()
	bindings := make([]DeviceBinding, 0, 2)
	for rows.Next() {
		b, err := scanBinding(rows)
		if err != nil {
			return nil, nil, 0, err
		}
		if b != nil {
			bindings = append(bindings, *b)
		}
	}
	if err := rows.Err(); err != nil {
		return nil, nil, 0, err
	}

	var verifyCount int64
	if len(bindings) > 0 {
		for _, b := range bindings {
			verifyCount += b.VerifyCount
		}
	}
	if len(bindings) > 0 && bindings[0].DeviceLabel != "" {
		it.BoundDevice = bindings[0].DeviceLabel
	}
	return it, bindings, verifyCount, nil
}

// LogFilter 使用记录筛选。
type LogFilter struct {
	CodeID int64
	Action string // activate/verify/rebind/revoke | 空=全部
	Result string // success | fail | 空=全部
	From   int64
	To     int64
	Page   int
	Size   int
}

// listLogs 使用记录分页（按时间倒序），并带设备标签。
func listLogs(db *sql.DB, f LogFilter) ([]LogItem, int64, error) {
	var conds []string
	var args []interface{}
	conds = append(conds, "l.code_id = ?")
	args = append(args, f.CodeID)
	if f.Action != "" {
		conds = append(conds, "l.action = ?")
		args = append(args, f.Action)
	}
	switch f.Result {
	case "success":
		conds = append(conds, "l.result_code = 0")
	case "fail":
		conds = append(conds, "l.result_code <> 0")
	}
	if f.From > 0 {
		conds = append(conds, "l.created_at >= ?")
		args = append(args, f.From)
	}
	if f.To > 0 {
		conds = append(conds, "l.created_at <= ?")
		args = append(args, f.To)
	}
	where := " WHERE " + strings.Join(conds, " AND ")
	base := ` FROM verify_log l
		LEFT JOIN device_binding b ON b.code_id = l.code_id AND b.device_hash = l.device_hash` + where

	var total int64
	if err := db.QueryRow(`SELECT COUNT(*)`+base, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	page, size := normalizePage(f.Page, f.Size)
	rows, err := db.Query(`SELECT l.id, l.created_at, l.action, l.result_code, l.device_hash,
		COALESCE(b.device_label, ''), l.ip, l.ua`+base+`
		ORDER BY l.created_at DESC, l.id DESC LIMIT ? OFFSET ?`,
		append(args, size, (page-1)*size)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	items := make([]LogItem, 0, size)
	for rows.Next() {
		var it LogItem
		if err := rows.Scan(&it.ID, &it.CreatedAt, &it.Action, &it.ResultCode, &it.DeviceHash,
			&it.DeviceLabel, &it.IP, &it.UA); err != nil {
			return nil, 0, err
		}
		it.ResultText = codeMessage(it.ResultCode)
		items = append(items, it)
	}
	return items, total, rows.Err()
}

// overviewStats 概览统计。
func overviewStats(db *sql.DB) (*Overview, error) {
	now := nowMs()
	o := &Overview{ByTier: map[string]int64{}, ByType: map[string]int64{}}
	if err := db.QueryRow(`SELECT COUNT(*) FROM license_code`).Scan(&o.Total); err != nil {
		return nil, err
	}
	usageQueries := map[string]string{
		"unused":  "SELECT COUNT(*) FROM license_code c WHERE c.status='active' AND c.activated_at IS NULL",
		"active":  "SELECT COUNT(*) FROM license_code c WHERE c.status='active' AND c.activated_at IS NOT NULL AND (c.type='permanent' OR c.expire_at IS NULL OR c.expire_at > ?)",
		"expired": "SELECT COUNT(*) FROM license_code c WHERE c.status='active' AND c.type='month' AND c.expire_at IS NOT NULL AND c.expire_at <= ?",
		"revoked": "SELECT COUNT(*) FROM license_code c WHERE c.status='revoked'",
	}
	for k, q := range usageQueries {
		var n int64
		var err error
		if strings.Contains(q, "?") {
			err = db.QueryRow(q, now).Scan(&n)
		} else {
			err = db.QueryRow(q).Scan(&n)
		}
		if err != nil {
			return nil, err
		}
		switch k {
		case "unused":
			o.Unused = n
		case "active":
			o.Active = n
		case "expired":
			o.Expired = n
		case "revoked":
			o.Revoked = n
		}
	}
	if err := db.QueryRow(`SELECT COUNT(*) FROM license_code WHERE created_at >= ?`,
		now-7*MsPerDay).Scan(&o.New7d); err != nil {
		return nil, err
	}
	if err := db.QueryRow(`SELECT COUNT(*) FROM license_code WHERE activated_at IS NOT NULL AND activated_at >= ?`,
		now-7*MsPerDay).Scan(&o.Activated7d); err != nil {
		return nil, err
	}

	rows, err := db.Query(`SELECT CASE WHEN is_gm=1 THEN 'gm' ELSE CAST(tier AS TEXT) END AS k,
		COUNT(*) FROM license_code GROUP BY k`)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var k string
		var n int64
		if err := rows.Scan(&k, &n); err != nil {
			rows.Close()
			return nil, err
		}
		o.ByTier[k] = n
	}
	rows.Close()

	rows, err = db.Query(`SELECT type, COUNT(*) FROM license_code GROUP BY type`)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var k string
		var n int64
		if err := rows.Scan(&k, &n); err != nil {
			rows.Close()
			return nil, err
		}
		o.ByType[k] = n
	}
	rows.Close()

	audits, _, err := listAudit(db, 1, 20)
	if err != nil {
		return nil, err
	}
	o.RecentAudit = audits
	return o, nil
}

// insertAdminAudit 写一条管理操作审计（失败只告警，不阻断主流程）。
func insertAdminAudit(db *sql.DB, actor, action, targetType, targetID, detail, ip string) {
	_, err := db.Exec(`INSERT INTO admin_audit
		(created_at, actor, action, target_type, target_id, detail, ip)
		VALUES (?, ?, ?, ?, ?, ?, ?)`,
		nowMs(), actor, action, targetType, targetID, detail, ip)
	if err != nil {
		logWarn("写入 admin_audit 失败: %v", err)
	}
}

// listAudit 审计分页（时间倒序）。
func listAudit(db *sql.DB, page, size int) ([]AdminAuditItem, int64, error) {
	page, size = normalizePage(page, size)
	var total int64
	if err := db.QueryRow(`SELECT COUNT(*) FROM admin_audit`).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := db.Query(`SELECT id, created_at, actor, action, target_type, target_id, detail, ip
		FROM admin_audit ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?`, size, (page-1)*size)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	items := make([]AdminAuditItem, 0, size)
	for rows.Next() {
		var it AdminAuditItem
		if err := rows.Scan(&it.ID, &it.CreatedAt, &it.Actor, &it.Action, &it.TargetType,
			&it.TargetID, &it.Detail, &it.IP); err != nil {
			return nil, 0, err
		}
		items = append(items, it)
	}
	return items, total, rows.Err()
}

// normalizePage 分页参数收敛：page>=1；size 20/50/100，默认 20；上限 500。
func normalizePage(page, size int) (int, int) {
	if page < 1 {
		page = 1
	}
	switch size {
	case 20, 50, 100, 500:
	default:
		size = 20
	}
	return page, size
}

// nullInt64OrZero 取 NullInt64 的值（无效返回 0）。
func nullInt64OrZero(v sql.NullInt64) int64 {
	if v.Valid {
		return v.Int64
	}
	return 0
}

// parseEpochQuery 解析管理端时间筛选（0 或非法 → 0）。
func parseEpochQuery(s string) int64 {
	if s == "" {
		return 0
	}
	var v int64
	if _, err := fmt.Sscanf(s, "%d", &v); err != nil || v < 0 {
		return 0
	}
	return v
}
