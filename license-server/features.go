package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"sort"
)

// ---------------------------------------------------------------------------
// 功能目录与角色权限矩阵（2026-11-06 管理后台）
//
// 唯一事实源：App 侧 FeatureGate.Feature 的 39 个 key。服务端只做"目录 + 矩阵"
// 的存储与下发，不重复实现 App 的判定逻辑：
//   - implemented=false（未开发/未立项）：任何角色都不可见（GM 也不行）；
//   - debugOnly=true（仅调试包）：release 包恒隐藏，矩阵不控制；
//   - 其余按"角色 → 可见 key"矩阵下发。
//
// 角色：1=T1 普通 / 2=T2 普通会员 / 3=T3 高级会员 / 4=GM（GM 恒为"已实现全集"，
// 只读、不落 role_feature，避免与 license_code.is_gm 双写不一致）。
// ---------------------------------------------------------------------------

// dbQuerier 兼容 *sql.DB 与 *sql.Tx 的最小查询接口。
// 背景：openDB 收敛为单连接（MaxOpenConns=1），事务独占连接期间再用 db 池查询
// 会自我阻塞（2026-11-06 实测死锁）；事务内必须改用 tx 查询。
type dbQuerier interface {
	Query(query string, args ...interface{}) (*sql.Rows, error)
	QueryRow(query string, args ...interface{}) *sql.Row
}

// FeatureCatalogItem 功能目录一行（与 App FeatureGate 对齐）。
type FeatureCatalogItem struct {
	Key         string `json:"key"`
	Label       string `json:"label"`
	DefaultTier int    `json:"defaultTier"`
	Implemented bool   `json:"implemented"`
	DebugOnly   bool   `json:"debugOnly"`
	Sort        int    `json:"sort"`
}

// FeatureRow 管理后台矩阵视图一行：功能元数据 + 四个角色的勾选态。
type FeatureRow struct {
	Key         string `json:"key"`
	Label       string `json:"label"`
	DefaultTier int    `json:"defaultTier"`
	Implemented bool   `json:"implemented"`
	DebugOnly   bool   `json:"debugOnly"`
	T1          bool   `json:"t1"`
	T2          bool   `json:"t2"`
	T3          bool   `json:"t3"`
	GM          bool   `json:"gm"`
}

// MatrixCell 矩阵保存输入（前端提交 T1/T2/T3 三列全量）。
type MatrixCell struct {
	Key     string `json:"key"`
	Role    int    `json:"role"`
	Visible bool   `json:"visible"`
}

// MatrixUpdateReq PUT /admin/api/matrix 请求体。
type MatrixUpdateReq struct {
	Rows []MatrixCell `json:"rows"`
}

// featureSeed 39 项目录（顺序 = App FeatureGate 枚举顺序）。
// key / 中文名 / 默认等级 / 已实现 / 仅调试包。
var featureSeed = []FeatureCatalogItem{
	// ===== T1 普通：分身 / 账号 / 启动 / 基础设置 =====
	{"account_pool", "账号池", 1, true, false, 1},
	{"account_add", "添加分身账号", 1, true, false, 2},
	{"account_start", "启动分身", 1, true, false, 3},
	{"basic_settings", "基础设置", 1, true, false, 4},
	{"license_manage", "授权管理", 1, true, false, 5},

	// ===== T2 会员：日常脚本 =====
	{"today_tab", "今日页", 2, true, false, 6},
	{"task_tab", "日常页", 2, true, false, 7},
	{"automation_status", "自动化状态", 2, true, false, 8},
	{"daily_config", "任务/日常配置", 2, true, false, 9},
	{"seven_modules", "七模块任务", 2, true, false, 10},
	{"notify_settings", "通知设置", 2, true, false, 11},
	{"run_progress", "执行进度/日志", 2, true, false, 12},
	{"keep_alive", "定时与保活", 2, true, false, 13},
	{"aid_alliance", "一键帮助全盟", 2, true, false, 14},

	// ===== T3 高级会员：高阶已实现 =====
	{"enemy_watch", "敌情监控", 3, true, false, 15},
	{"dev_monitor", "真机监控", 3, true, false, 16},
	{"toolbox", "工具箱", 3, true, false, 17},
	{"guardian", "守护盟友", 3, true, false, 18},
	{"watchpoint", "观察点", 3, true, false, 19},
	{"proto_capture", "真机抓包", 3, true, false, 20},

	// ===== T3 高级会员：尚未实现（即使 GM 也隐藏） =====
	{"move_city", "迁城", 3, false, false, 21},
	{"coord_query", "坐标查询", 3, false, false, 22},
	{"recharge_record", "充值记录", 3, false, false, 23},
	{"behemoth_batch", "巨兽批量", 3, false, false, 24},

	// ===== T2 日常例外项 =====
	{"war_state", "战争状态", 2, false, false, 25},
	{"backpack_items", "背包道具", 2, false, false, 26},
	{"behemoth_autojoin", "巨兽狩猎（仅原生自动上车）", 2, true, false, 27},

	// ===== 未开发/未立项 =====
	{"ranking", "排行榜", 3, false, false, 28},
	{"rally_attack", "进攻/撞矿", 3, false, false, 29},
	{"account_farm", "起号", 3, false, false, 30},
	{"activity_rank", "活动榜单", 3, false, false, 31},
	{"match3", "三消", 3, false, false, 32},
	{"troop_rush", "爆兵", 3, false, false, 33},
	{"pet_reset", "宠物洗练", 3, false, false, 34},
	// 2026-11-07：移民已按立项实现（vault-server/migrant + App migrant 工作区），
	// 目录必须置 implemented=true，否则 saveMatrix 拒绝勾选、featuresForRole 的
	// `fc.implemented = 1` 过滤也不会下发 → T3 用户永远看不到入口。
	{"immigration", "移民", 3, true, false, 35},
	{"recon_report", "侦察战报", 3, false, false, 36},
	// 2026-11-07：App 侧编队管理已 T2 实现在线（决策6），目录此前仍为 3/false，
	// 会导致矩阵模式下 T2/T3 都拿不到 formation_manage（编队管理入口消失）。
	{"formation_manage", "编队管理", 2, true, false, 37},

	// ===== 调试/实测项（仅调试包，矩阵不控制） =====
	{"protocol_logger", "协议监听", 1, true, true, 38},
	{"protocol_probe", "协议探针", 1, true, true, 39},
}

// seedFeatureCatalog 启动时 upsert 功能目录（保持二进制内 seed 为最新）。
// 只更新目录元数据，不触碰 role_feature（管理员配置保留）。
func seedFeatureCatalog(db *sql.DB) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, it := range featureSeed {
		if _, err := tx.Exec(`INSERT INTO feature_catalog
			(key, label, default_tier, implemented, debug_only, sort)
			VALUES (?, ?, ?, ?, ?, ?)
			ON CONFLICT(key) DO UPDATE SET
				label = excluded.label,
				default_tier = excluded.default_tier,
				implemented = excluded.implemented,
				debug_only = excluded.debug_only,
				sort = excluded.sort`,
			it.Key, it.Label, it.DefaultTier, boolToInt(it.Implemented), boolToInt(it.DebugOnly), it.Sort); err != nil {
			return fmt.Errorf("upsert feature_catalog %s: %w", it.Key, err)
		}
	}
	return tx.Commit()
}

// ensureRoleFeatureSeed 写入默认矩阵：空表全量写默认；非空表只补「缺失的 (role,key) 行」。
//
// 2026-11-07 变更：旧实现「表非空即完全跳过」会让新增功能 key 永远没有矩阵行 →
// featuresForRole 的 JOIN 查不到 → 新功能（如 immigration/formation_manage）即使目录已置
// implemented 也不会下发。改为 INSERT OR IGNORE：缺失行按 defaultVisible 补齐，
// 运营已保存的配置**绝不覆盖**（幂等，每次启动安全）。
func ensureRoleFeatureSeed(db *sql.DB) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, role := range []int{1, 2, 3} {
		for _, it := range featureSeed {
			v := 0
			if defaultVisible(role, it) {
				v = 1
			}
			if _, err := tx.Exec(`INSERT OR IGNORE INTO role_feature (role, feature_key, visible)
				VALUES (?, ?, ?)`, role, it.Key, v); err != nil {
				return err
			}
		}
	}
	return tx.Commit()
}

// defaultVisible 默认矩阵规则：已实现、非调试、默认等级不高于该角色。
// T1<T2<T3 逐级包含，便于运营从"现行硬编码"平滑起步。
func defaultVisible(role int, it FeatureCatalogItem) bool {
	if !it.Implemented || it.DebugOnly {
		return false
	}
	if role < 1 || role > 3 {
		return false
	}
	return it.DefaultTier <= role
}

// roleOfCode 码 → 角色档位：GM 优先（4），否则按 tier 1/2/3。
func roleOfCode(c *LicenseCode) int {
	if c == nil {
		return 1
	}
	if c.IsGM {
		return RoleGM
	}
	switch c.Tier {
	case TierBasic:
		return 1
	case TierMember:
		return 2
	default:
		return 3
	}
}

// featuresForRole 计算某角色可见的功能 key（按字典序）。
// GM：已实现且非调试的全集；T1/T2/T3：查 role_feature 并过滤未实现/调试项。
func featuresForRole(db dbQuerier, role int) ([]string, error) {
	keys := make([]string, 0, len(featureSeed))
	if role == RoleGM {
		for _, it := range featureSeed {
			if it.Implemented && !it.DebugOnly {
				keys = append(keys, it.Key)
			}
		}
		sort.Strings(keys)
		return keys, nil
	}
	if role < 1 || role > 3 {
		return keys, nil
	}
	rows, err := db.Query(`SELECT rf.feature_key
		FROM role_feature rf
		JOIN feature_catalog fc ON fc.key = rf.feature_key
		WHERE rf.role = ? AND rf.visible = 1 AND fc.implemented = 1 AND fc.debug_only = 0
		ORDER BY rf.feature_key`, role)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var k string
		if err := rows.Scan(&k); err != nil {
			return nil, err
		}
		keys = append(keys, k)
	}
	return keys, rows.Err()
}

// loadCatalog 读取功能目录（按 sort 排序）。
func loadCatalog(db dbQuerier) ([]FeatureCatalogItem, error) {
	rows, err := db.Query(`SELECT key, label, default_tier, implemented, debug_only, sort
		FROM feature_catalog ORDER BY sort, key`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]FeatureCatalogItem, 0, len(featureSeed))
	for rows.Next() {
		var (
			it         FeatureCatalogItem
			impl, dbg  int
			defaultTie int
		)
		if err := rows.Scan(&it.Key, &it.Label, &defaultTie, &impl, &dbg, &it.Sort); err != nil {
			return nil, err
		}
		it.DefaultTier = defaultTie
		it.Implemented = impl != 0
		it.DebugOnly = dbg != 0
		out = append(out, it)
	}
	return out, rows.Err()
}

// loadMatrix 读取 T1/T2/T3 三列矩阵（key → role → visible）。
func loadMatrix(db dbQuerier) (map[int]map[string]bool, error) {
	m := map[int]map[string]bool{1: {}, 2: {}, 3: {}}
	rows, err := db.Query(`SELECT role, feature_key, visible FROM role_feature`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var (
			role, visible int
			key           string
		)
		if err := rows.Scan(&role, &key, &visible); err != nil {
			return nil, err
		}
		if _, ok := m[role]; !ok {
			m[role] = map[string]bool{}
		}
		m[role][key] = visible != 0
	}
	return m, rows.Err()
}

// buildFeatureRows 组合目录与矩阵为后台矩阵视图（GM 恒为已实现全集、只读）。
func buildFeatureRows(db dbQuerier) ([]FeatureRow, error) {
	catalog, err := loadCatalog(db)
	if err != nil {
		return nil, err
	}
	m, err := loadMatrix(db)
	if err != nil {
		return nil, err
	}
	out := make([]FeatureRow, 0, len(catalog))
	for _, it := range catalog {
		gm := it.Implemented && !it.DebugOnly
		out = append(out, FeatureRow{
			Key:         it.Key,
			Label:       it.Label,
			DefaultTier: it.DefaultTier,
			Implemented: it.Implemented,
			DebugOnly:   it.DebugOnly,
			T1:          m[1][it.Key],
			T2:          m[2][it.Key],
			T3:          m[3][it.Key],
			GM:          gm,
		})
	}
	return out, nil
}

// matrixDiff 计算旧/新矩阵差异（用于审计 detail）。
type matrixDiff struct {
	Key    string `json:"key"`
	Role   int    `json:"role"`
	Before bool   `json:"before"`
	After  bool   `json:"after"`
}

// saveMatrix 校验并保存 T1/T2/T3 全量矩阵（单事务），返回变更 diff。
//
// 校验：
//   - key 必须在功能目录中；
//   - role 只能是 1/2/3（GM 只读）；
//   - 未实现/仅调试项不允许 visible=true；
//   - 每个目录 key 的 1/2/3 三格都必须出现（防前端漏提交导致静默关闭）。
func saveMatrix(db *sql.DB, cells []MatrixCell) ([]matrixDiff, error) {
	return saveMatrixAudited(db, cells, "admin", "")
}

// saveMatrixAudited 保存矩阵并把审计写进同一事务（操作成功必有审计）。
func saveMatrixAudited(db *sql.DB, cells []MatrixCell, actor, ip string) ([]matrixDiff, error) {
	catalog, err := loadCatalog(db)
	if err != nil {
		return nil, err
	}
	byKey := make(map[string]FeatureCatalogItem, len(catalog))
	for _, it := range catalog {
		byKey[it.Key] = it
	}
	seen := make(map[string]map[int]bool, len(catalog))
	for _, c := range cells {
		it, ok := byKey[c.Key]
		if !ok {
			return nil, fmt.Errorf("未知功能 key: %s", c.Key)
		}
		if c.Role < 1 || c.Role > 3 {
			return nil, fmt.Errorf("非法角色: %d（只允许 1/2/3）", c.Role)
		}
		if c.Visible && (!it.Implemented || it.DebugOnly) {
			return nil, fmt.Errorf("功能 %s 未实现或仅调试包，不可开放", c.Key)
		}
		if seen[c.Key] == nil {
			seen[c.Key] = map[int]bool{}
		}
		if seen[c.Key][c.Role] {
			return nil, fmt.Errorf("重复提交: %s/role%d", c.Key, c.Role)
		}
		seen[c.Key][c.Role] = true
	}
	for _, it := range catalog {
		for _, role := range []int{1, 2, 3} {
			if !seen[it.Key][role] {
				return nil, fmt.Errorf("矩阵不完整：缺少 %s/role%d", it.Key, role)
			}
		}
	}

	tx, err := db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	// diff 必须在写入前计算（tx 此刻读到的是旧矩阵）；
	// 且必须用 tx 查询（单连接池：事务内再用 db 会死锁）。
	diffs, err := matrixDiffOf(tx, cells)
	if err != nil {
		return nil, err
	}
	if _, err := tx.Exec(`DELETE FROM role_feature`); err != nil {
		return nil, err
	}
	for _, c := range cells {
		if _, err := tx.Exec(`INSERT INTO role_feature (role, feature_key, visible)
			VALUES (?, ?, ?)`, c.Role, c.Key, boolToInt(c.Visible)); err != nil {
			return nil, err
		}
	}
	// 审计与矩阵同事务（2026-11-06 审查 S6）：审计失败则整体回滚。
	if err := insertAdminAuditTx(tx, actor, AdminActionMatrixSave, "role_feature", "",
		marshalJSON(map[string]interface{}{"changes": diffs}), ip); err != nil {
		return nil, fmt.Errorf("写入审计失败，矩阵未保存: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return diffs, nil
}

// matrixDiffOf 计算全量提交相对当前库的 diff（saveMatrixAudited 事务内用）。
func matrixDiffOf(q dbQuerier, cells []MatrixCell) ([]matrixDiff, error) {
	catalog, err := loadCatalog(q)
	if err != nil {
		return nil, err
	}
	oldM, err := loadMatrix(q)
	if err != nil {
		return nil, err
	}
	newM := map[int]map[string]bool{1: {}, 2: {}, 3: {}}
	for _, c := range cells {
		newM[c.Role][c.Key] = c.Visible
	}
	diffs := make([]matrixDiff, 0)
	for role := 1; role <= 3; role++ {
		for _, it := range catalog {
			before := oldM[role][it.Key]
			after := newM[role][it.Key]
			if before != after {
				diffs = append(diffs, matrixDiff{Key: it.Key, Role: role, Before: before, After: after})
			}
		}
	}
	return diffs, nil
}

// resetMatrix 恢复默认矩阵（按 featureSeed 规则），返回变更 diff。
func resetMatrix(db *sql.DB) ([]matrixDiff, error) {
	return resetMatrixAudited(db, "admin", "")
}

// resetMatrixAudited 恢复默认矩阵并把审计写进同一事务。
func resetMatrixAudited(db *sql.DB, actor, ip string) ([]matrixDiff, error) {
	oldM, err := loadMatrix(db)
	if err != nil {
		return nil, err
	}
	tx, err := db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`DELETE FROM role_feature`); err != nil {
		return nil, err
	}
	for _, role := range []int{1, 2, 3} {
		for _, it := range featureSeed {
			v := 0
			if defaultVisible(role, it) {
				v = 1
			}
			if _, err := tx.Exec(`INSERT INTO role_feature (role, feature_key, visible)
				VALUES (?, ?, ?)`, role, it.Key, v); err != nil {
				return nil, err
			}
		}
	}
	diffs := make([]matrixDiff, 0)
	for role := 1; role <= 3; role++ {
		for _, it := range featureSeed {
			after := defaultVisible(role, it)
			if oldM[role][it.Key] != after {
				diffs = append(diffs, matrixDiff{Key: it.Key, Role: role, Before: oldM[role][it.Key], After: after})
			}
		}
	}
	if err := insertAdminAuditTx(tx, actor, AdminActionMatrixReset, "role_feature", "",
		marshalJSON(map[string]interface{}{"changes": diffs}), ip); err != nil {
		return nil, fmt.Errorf("写入审计失败，矩阵未重置: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return diffs, nil
}

// boolToInt SQLite 存整数。
func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

// marshalJSON 便捷 JSON 序列化（审计 detail 用），失败返回 "{}"。
func marshalJSON(v interface{}) string {
	b, err := json.Marshal(v)
	if err != nil {
		return "{}"
	}
	return string(b)
}

// matrixSchemaVersion 矩阵一次性迁移版本（SQLite PRAGMA user_version）。
const matrixSchemaVersion = 1

// migrateMatrixNewlyImplemented 一次性修正「由未实现转为已实现」的功能矩阵行。
//
// 背景（2026-11-07）：角色矩阵种子只在空表时写入；旧库中 immigration / formation_manage
// 的 role_feature 行是「未实现恒 0」的占位值，**不是运营决策**。目录 implemented 改为 1 后
// featuresForRole 的 `fc.implemented = 1` 过滤虽放开，但这些行仍为 0 → 依旧不下发，
// 表现为「后台标已实现、管理员也能勾，但 App 拿不到 key」。
//
// 用 SQLite user_version 做一次性迁移（幂等）：跑过一次后，运营可以自由开关，
// 重启不再覆盖（避免把运营配置冲掉）。
func migrateMatrixNewlyImplemented(db *sql.DB) error {
	var v int
	if err := db.QueryRow(`PRAGMA user_version`).Scan(&v); err != nil {
		return err
	}
	if v >= matrixSchemaVersion {
		return nil
	}
	// 2026-11-07 由「未实现」转为「已实现」的 key（后续再有类似变更，追加到此表并升版本号）。
	targets := map[string]bool{"immigration": true, "formation_manage": true}
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, it := range featureSeed {
		if !targets[it.Key] {
			continue
		}
		for _, role := range []int{1, 2, 3} {
			vv := 0
			if defaultVisible(role, it) {
				vv = 1
			}
			if _, err := tx.Exec(`UPDATE role_feature SET visible = ? WHERE role = ? AND feature_key = ?`,
				vv, role, it.Key); err != nil {
				return err
			}
		}
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	if _, err := db.Exec(`PRAGMA user_version = 1`); err != nil {
		return err
	}
	return nil
}
