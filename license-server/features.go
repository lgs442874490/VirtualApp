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
// 唯一事实源：App 侧 FeatureGate.Feature 的 38 个 key。服务端只做"目录 + 矩阵"
// 的存储与下发，不重复实现 App 的判定逻辑：
//   - implemented=false（未开发/未立项）：任何角色都不可见（GM 也不行）；
//   - debugOnly=true（仅调试包）：release 包恒隐藏，矩阵不控制；
//   - 其余按"角色 → 可见 key"矩阵下发。
//
// 角色：1=T1 普通 / 2=T2 普通会员 / 3=T3 高级会员 / 4=GM（GM 恒为"已实现全集"，
// 只读、不落 role_feature，避免与 license_code.is_gm 双写不一致）。
// ---------------------------------------------------------------------------

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

// featureSeed 38 项目录（顺序 = App FeatureGate 枚举顺序）。
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
	{"immigration", "移民抢位", 3, false, false, 35},
	{"recon_report", "侦察战报", 3, false, false, 36},
	{"formation_manage", "编队管理", 3, false, false, 37},

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

// ensureRoleFeatureSeed 仅当 role_feature 为空时写入默认矩阵（不覆盖管理员配置）。
func ensureRoleFeatureSeed(db *sql.DB) error {
	var n int64
	if err := db.QueryRow(`SELECT COUNT(*) FROM role_feature`).Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return nil
	}
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
			if _, err := tx.Exec(`INSERT OR REPLACE INTO role_feature (role, feature_key, visible)
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
func featuresForRole(db *sql.DB, role int) ([]string, error) {
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
func loadCatalog(db *sql.DB) ([]FeatureCatalogItem, error) {
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
func loadMatrix(db *sql.DB) (map[int]map[string]bool, error) {
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
func buildFeatureRows(db *sql.DB) ([]FeatureRow, error) {
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
	for _, c := range cells {
		if _, err := tx.Exec(`INSERT INTO role_feature (role, feature_key, visible)
			VALUES (?, ?, ?)`, c.Role, c.Key, boolToInt(c.Visible)); err != nil {
			return nil, err
		}
	}
	if err := tx.Commit(); err != nil {
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
	if err := tx.Commit(); err != nil {
		return nil, err
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
