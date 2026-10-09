package main

import (
	"database/sql"
	"encoding/csv"
	"flag"
	"fmt"
	"os"
	"text/tabwriter"
	"time"
)

// fmtTimeLocal 将 epoch 毫秒转成本地时区可读字符串。
func fmtTimeLocal(ms int64) string {
	if ms <= 0 {
		return "-"
	}
	return time.UnixMilli(ms).Local().Format("2006-01-02 15:04:05")
}

// remainDays 计算剩余天数（向上取整，最小为 0）。
func remainDays(expireAt, now int64) int64 {
	d := expireAt - now
	if d <= 0 {
		return 0
	}
	return (d + MsPerDay - 1) / MsPerDay
}

// resolveCode 根据 --code 或 --code-id 解析目标码，返回 (codeID, code, err)；未找到返回 code=nil。
func resolveCode(db *sql.DB, codeFlag string, idFlag int64) (int64, *LicenseCode, error) {
	if idFlag > 0 {
		c, err := getCodeByID(db, idFlag)
		if err != nil {
			return 0, nil, err
		}
		return idFlag, c, nil
	}
	if codeFlag == "" {
		return 0, nil, fmt.Errorf("必须提供 --code 或 --code-id")
	}
	c, err := getCodeByCode(db, canonicalCode(codeFlag))
	if err != nil {
		return 0, nil, err
	}
	if c == nil {
		return 0, nil, nil
	}
	return c.ID, c, nil
}

// ---------------------------------------------------------------------------
// initdb
// ---------------------------------------------------------------------------

// cmdInitDB 初始化数据库（幂等，可反复执行）。
func cmdInitDB(args []string) error {
	cfg := defaultConfig()
	fs := flag.NewFlagSet("initdb", flag.ExitOnError)
	registerDBFlag(fs, cfg)
	if err := fs.Parse(args); err != nil {
		return err
	}
	db, err := openDB(cfg.DBPath)
	if err != nil {
		return err
	}
	defer db.Close()
	fmt.Printf("initdb 完成：数据库已就绪 -> %s\n", cfg.DBPath)
	return nil
}

// ---------------------------------------------------------------------------
// issue
// ---------------------------------------------------------------------------

// cmdIssue 批量发码。
func cmdIssue(args []string) error {
	cfg := defaultConfig()
	fs := flag.NewFlagSet("issue", flag.ExitOnError)
	registerDBFlag(fs, cfg)
	typ := fs.String("type", "month", "码类型：month | permanent")
	count := fs.Int("count", 1, "生成数量")
	remark := fs.String("remark", "", "备注（运营用）")
	out := fs.String("out", "", "可选：导出 CSV 文件路径")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *typ != TypeMonth && *typ != TypePermanent {
		return fmt.Errorf("--type 只能是 month 或 permanent，收到 %q", *typ)
	}
	if *count <= 0 {
		return fmt.Errorf("--count 必须为正整数")
	}

	db, err := openDB(cfg.DBPath)
	if err != nil {
		return err
	}
	defer db.Close()

	durationDays := int64(0)
	if *typ == TypeMonth {
		durationDays = MonthDurationDays
	}
	now := nowMs()
	createdBy := envOr("USER", "cli")

	codes := make([]string, 0, *count)
	for i := 0; i < *count; i++ {
		code, err := genUniqueCode(db)
		if err != nil {
			return err
		}
		if _, err := insertCode(db, code, *typ, durationDays, *remark, createdBy, now); err != nil {
			return fmt.Errorf("写入兑换码失败: %w", err)
		}
		codes = append(codes, code)
	}

	fmt.Printf("已生成 %d 个兑换码（type=%s）：\n", len(codes), *typ)
	for _, c := range codes {
		fmt.Println("  " + c)
	}

	if *out != "" {
		if err := writeCodesCSV(*out, codes, *typ, durationDays, *remark, now); err != nil {
			return err
		}
		fmt.Printf("已导出 CSV -> %s\n", *out)
	}
	return nil
}

// genUniqueCode 生成一个库中尚不存在的兑换码（碰撞则重试）。
func genUniqueCode(db *sql.DB) (string, error) {
	for attempt := 0; attempt < 10; attempt++ {
		code, err := genCode()
		if err != nil {
			return "", err
		}
		existing, err := getCodeByCode(db, code)
		if err != nil {
			return "", err
		}
		if existing == nil {
			return code, nil
		}
	}
	return "", fmt.Errorf("生成唯一兑换码失败：连续 10 次碰撞，请检查随机源")
}

// writeCodesCSV 将本批码导出为 CSV。
func writeCodesCSV(path string, codes []string, typ string, durationDays int64, remark string, createdAt int64) error {
	f, err := os.Create(path)
	if err != nil {
		return fmt.Errorf("创建 CSV 失败: %w", err)
	}
	defer f.Close()
	wr := csv.NewWriter(f)
	defer wr.Flush()
	if err := wr.Write([]string{"code", "type", "durationDays", "remark", "createdAt"}); err != nil {
		return err
	}
	createdStr := fmtTimeLocal(createdAt)
	for _, c := range codes {
		if err := wr.Write([]string{c, typ, strconvI64(durationDays), remark, createdStr}); err != nil {
			return err
		}
	}
	return wr.Error()
}

// ---------------------------------------------------------------------------
// revoke
// ---------------------------------------------------------------------------

// cmdRevoke 吊销兑换码。
func cmdRevoke(args []string) error {
	cfg := defaultConfig()
	fs := flag.NewFlagSet("revoke", flag.ExitOnError)
	registerDBFlag(fs, cfg)
	code := fs.String("code", "", "要吊销的兑换码")
	codeID := fs.Int64("code-id", 0, "要吊销的码 ID（与 --code 二选一）")
	reason := fs.String("reason", "", "吊销原因")
	if err := fs.Parse(args); err != nil {
		return err
	}
	db, err := openDB(cfg.DBPath)
	if err != nil {
		return err
	}
	defer db.Close()

	id, c, err := resolveCode(db, *code, *codeID)
	if err != nil {
		return err
	}
	if c == nil {
		return fmt.Errorf("未找到兑换码（code=%q, code-id=%d）", *code, *codeID)
	}
	if c.Status == StatusRevoked {
		fmt.Printf("兑换码 %s 已处于 revoked 状态，无需重复操作。\n", c.Code)
		return nil
	}
	now := nowMs()
	if err := revokeCodeByID(db, id, *reason, now); err != nil {
		return fmt.Errorf("吊销失败: %w", err)
	}
	insertLog(db, codeIDPtr(id), "", ActionRevoke, CodeOK, "", "", now)
	fmt.Printf("已吊销兑换码：%s（id=%d, reason=%q）\n", c.Code, id, *reason)
	return nil
}

// ---------------------------------------------------------------------------
// list
// ---------------------------------------------------------------------------

// cmdList 列出兑换码。
func cmdList(args []string) error {
	cfg := defaultConfig()
	fs := flag.NewFlagSet("list", flag.ExitOnError)
	registerDBFlag(fs, cfg)
	status := fs.String("status", "all", "过滤状态：active | revoked | all")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *status != "all" && *status != StatusActive && *status != StatusRevoked {
		return fmt.Errorf("--status 只能是 active | revoked | all，收到 %q", *status)
	}
	db, err := openDB(cfg.DBPath)
	if err != nil {
		return err
	}
	defer db.Close()

	query := `SELECT c.code, c.type, c.status, c.created_at, c.activated_at, c.expire_at,
		COALESCE(b.device_label, ''), b.bound_at
		FROM license_code c
		LEFT JOIN device_binding b ON b.code_id = c.id AND b.status = 'active'`
	var (
		rows *sql.Rows
		qerr error
	)
	if *status == "all" {
		rows, qerr = db.Query(query + ` ORDER BY c.id`)
	} else {
		rows, qerr = db.Query(query+` WHERE c.status = ? ORDER BY c.id`, *status)
	}
	if qerr != nil {
		return fmt.Errorf("查询失败: %w", qerr)
	}
	defer rows.Close()

	now := nowMs()
	tw := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(tw, "CODE\tTYPE\tSTATUS\tDEVICE\tACTIVATED\tEXPIRE\tREMAIN")
	total := 0
	for rows.Next() {
		var (
			code, typ, st, label  string
			createdAt             int64
			activatedAt, expireAt sql.NullInt64
			boundAt               sql.NullInt64
		)
		if err := rows.Scan(&code, &typ, &st, &createdAt, &activatedAt, &expireAt, &label, &boundAt); err != nil {
			return fmt.Errorf("扫描行失败: %w", err)
		}
		activatedStr := "-"
		if activatedAt.Valid {
			activatedStr = fmtTimeLocal(activatedAt.Int64)
		}
		expireStr := "-"
		remainStr := "-"
		if st == StatusRevoked {
			remainStr = "已停用"
			if expireAt.Valid {
				expireStr = fmtTimeLocal(expireAt.Int64)
			}
		} else if typ == TypePermanent {
			remainStr = "永久"
		} else if expireAt.Valid {
			expireStr = fmtTimeLocal(expireAt.Int64)
			remainStr = strconvI64(remainDays(expireAt.Int64, now)) + " 天"
		}
		if label == "" {
			label = "-"
		}
		fmt.Fprintf(tw, "%s\t%s\t%s\t%s\t%s\t%s\t%s\n",
			code, typ, st, label, activatedStr, expireStr, remainStr)
		total++
	}
	if err := rows.Err(); err != nil {
		return err
	}
	tw.Flush()
	fmt.Printf("\n共 %d 条记录（status=%s）\n", total, *status)
	return nil
}

// ---------------------------------------------------------------------------
// rebind（CLI 直连本地库）
// ---------------------------------------------------------------------------

// cmdRebindCLI 由 CLI 直接对本地库执行换绑（需 --admin-token 通过鉴权）。
func cmdRebindCLI(args []string) error {
	cfg := defaultConfig()
	fs := flag.NewFlagSet("rebind", flag.ExitOnError)
	registerDBFlag(fs, cfg)
	code := fs.String("code", "", "要换绑的兑换码")
	codeID := fs.Int64("code-id", 0, "要换绑的码 ID（与 --code 二选一）")
	newHash := fs.String("new-device-hash", "", "新设备指纹（加盐 SHA-256 hex）")
	reason := fs.String("reason", "", "换绑原因")
	adminToken := fs.String("admin-token", cfg.AdminToken, "管理令牌（env LICENSE_ADMIN_TOKEN）")
	rebindMax := fs.Int64("rebind-max", cfg.RebindMax, "换绑次数上限，默认 1，0=不限（env LICENSE_REBIND_MAX）")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *newHash == "" {
		return fmt.Errorf("必须提供 --new-device-hash")
	}
	if cfg.AdminToken == "" {
		return fmt.Errorf("LICENSE_ADMIN_TOKEN 未设置：无法执行换绑。请先 export LICENSE_ADMIN_TOKEN=... 或传 --admin-token")
	}
	if !constantTimeEqual(*adminToken, cfg.AdminToken) {
		return fmt.Errorf("管理员令牌校验失败（--admin-token 与 LICENSE_ADMIN_TOKEN 不一致）")
	}

	db, err := openDB(cfg.DBPath)
	if err != nil {
		return err
	}
	defer db.Close()

	id, c, err := resolveCode(db, *code, *codeID)
	if err != nil {
		return err
	}
	if c == nil {
		return fmt.Errorf("未找到兑换码（code=%q, code-id=%d）", *code, *codeID)
	}

	resCode, expireAt, rebindCount, err := performRebind(db, *rebindMax, c.Code, *newHash, *reason, nowMs(), "", "")
	if err != nil {
		return fmt.Errorf("换绑失败: %w", err)
	}
	if resCode != CodeOK {
		return fmt.Errorf("换绑被拒绝：%s（code=%d）", codeMessage(resCode), resCode)
	}
	fmt.Printf("换绑成功：code=%s id=%d\n", c.Code, id)
	fmt.Printf("  新设备哈希  : %s\n", *newHash)
	if expireAt > 0 {
		fmt.Printf("  到期时间    : %s\n", fmtTimeLocal(expireAt))
	} else {
		fmt.Printf("  到期时间    : 永久\n")
	}
	fmt.Printf("  历史换绑次数: %d\n", rebindCount)
	return nil
}

// performRebind 换绑核心逻辑（HTTP 与 CLI 共用）。
// 返回 (业务码, expireAt, rebindCount, error)；业务码非 0 表示被拒绝。
func performRebind(db *sql.DB, rebindMax int64, codeStr, newHash, reason string, now int64, ip, ua string) (int, int64, int64, error) {
	canon := canonicalCode(codeStr)
	if !validateCode(canon) {
		return CodeFormatInvalid, 0, 0, nil
	}
	c, err := getCodeByCode(db, canon)
	if err != nil {
		return CodeServerError, 0, 0, err
	}
	if c == nil {
		return CodeNotFound, 0, 0, nil
	}

	// 换绑策略（用户拍板：一码一次激活、禁止换绑）：
	// rebindMax == 0 → 一律禁止；>0 → 每码限 N 次；<0 → 不限。
	if rebindMax == 0 {
		insertLog(db, codeIDPtr(c.ID), newHash, ActionRebind, CodeRebindLimit, ip, ua, now)
		return CodeRebindLimit, 0, 0, nil
	}
	cnt, err := countRebinds(db, c.ID)
	if err != nil {
		return CodeServerError, 0, 0, err
	}
	if rebindMax > 0 && cnt >= rebindMax {
		insertLog(db, codeIDPtr(c.ID), newHash, ActionRebind, CodeRebindLimit, ip, ua, now)
		return CodeRebindLimit, 0, 0, nil
	}

	// 旧绑定置 unbound（保留以便审计与换绑）
	oldBinding, err := getActiveBinding(db, c.ID)
	if err != nil {
		return CodeServerError, 0, 0, err
	}
	if oldBinding != nil && oldBinding.DeviceHash != newHash {
		if err := markBindingUnbound(db, oldBinding.ID); err != nil {
			return CodeServerError, 0, 0, err
		}
	}

	// 写入新绑定（保留 activated_at / expire_at，R2 起算点不受换绑影响）
	if err := upsertBinding(db, c.ID, newHash, "", now); err != nil {
		return CodeServerError, 0, 0, err
	}

	rebindCount := cnt + 1
	insertLog(db, codeIDPtr(c.ID), newHash, ActionRebind, CodeOK, ip, ua, now)

	expireAt := int64(0)
	if c.ExpireAt.Valid {
		expireAt = c.ExpireAt.Int64
	}
	_ = reason
	return CodeOK, expireAt, rebindCount, nil
}
