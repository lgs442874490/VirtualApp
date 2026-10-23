package main

import (
	"database/sql"
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
)

// tierTestNonceSeq 保证同一测试库内 nonce 唯一（防重放表会拒绝重复 nonce）。
var tierTestNonceSeq int64

func tierTestNonce(prefix string) string {
	n := atomic.AddInt64(&tierTestNonceSeq, 1)
	return prefix + "-" + strconv.FormatInt(nowMs(), 10) + "-" + strconv.FormatInt(n, 10)
}

// 批次7 · 兑换码三级权限（license-server 端）单测。
//
// 覆盖：
//   - tier 常量 / normalizeTier / parseTier（含非法值）；
//   - 旧库迁移：CREATE TABLE 无 tier 的库 openDB 后幂等补列，存量行 tier=DEFAULT 3（高级会员，兼容旧用户）；
//   - insertCode 默认等级=3、insertCodeWithTier 显式 1/2/3；
//   - activate 响应 ticket.tier、verify 响应 data.tier（旧/新等级码都验证）；
//   - 运维 CLI `issue --tier`（DB 落库 + CSV 导出列）与非法 --tier 拒绝；
//   - activate/verify 响应 JSON 增加 tier 字段且不影响既有字段（status 封闭集合回归见 model_test.go）。

// 旧版（批次7 之前）license_code 建表语句：无 tier 列。用于模拟生产旧库迁移。
const tierTestOldSchemaSQL = `
CREATE TABLE license_code (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    code          TEXT    NOT NULL UNIQUE,
    code_hash     TEXT    NOT NULL UNIQUE,
    type          TEXT    NOT NULL CHECK (type IN ('month','permanent')),
    duration_days INTEGER NOT NULL DEFAULT 30,
    status        TEXT    NOT NULL DEFAULT 'active'
                          CHECK (status IN ('active','revoked','exhausted')),
    remark        TEXT    NOT NULL DEFAULT '',
    created_at    INTEGER NOT NULL,
    activated_at  INTEGER,
    expire_at     INTEGER,
    revoked_at    INTEGER,
    revoke_reason TEXT,
    created_by    TEXT    NOT NULL DEFAULT ''
);`

// TestTierNormalizeAndParse：等级归一化与 CLI 解析纯函数。
func TestTierNormalizeAndParse(t *testing.T) {
	if TierBasic != 1 || TierMember != 2 || TierPremium != 3 {
		t.Fatalf("tier 常量必须为 1/2/3，实际 %d/%d/%d", TierBasic, TierMember, TierPremium)
	}
	if TierDefault != TierPremium {
		t.Fatalf("缺省等级必须是高级会员(3)以兼容存量，实际 %d", TierDefault)
	}
	for _, v := range []int{1, 2, 3} {
		if got := normalizeTier(v); got != v {
			t.Fatalf("normalizeTier(%d)=%d", v, got)
		}
	}
	for _, v := range []int{0, -1, 4, 99} {
		if got := normalizeTier(v); got != TierPremium {
			t.Fatalf("normalizeTier(%d) 应回落默认 3，实际 %d", v, got)
		}
	}

	ok := map[string]int{
		"": TierPremium, "3": TierPremium, "premium": TierPremium, "T3": TierPremium,
		"1": TierBasic, "basic": TierBasic, "T1": TierBasic,
		"2": TierMember, "member": TierMember, "t2": TierMember,
	}
	for in, want := range ok {
		got, err := parseTier(in)
		if err != nil || got != want {
			t.Fatalf("parseTier(%q)=(%d,%v)，期望 (%d,nil)", in, got, err, want)
		}
	}
	for _, bad := range []string{"0", "4", "-1", "gold", "abc"} {
		if _, err := parseTier(bad); err == nil {
			t.Fatalf("parseTier(%q) 应返回错误", bad)
		}
	}
	if tierName(TierBasic) != "普通" || tierName(TierMember) != "会员" || tierName(TierPremium) != "高级会员" {
		t.Fatalf("tierName 文案不符: %s/%s/%s",
			tierName(TierBasic), tierName(TierMember), tierName(TierPremium))
	}
}

// TestTierSchemaMigrationDefaultsToPremium：旧库（无 tier 列）迁移后列存在、存量行=3、重复 openDB 幂等。
func TestTierSchemaMigrationDefaultsToPremium(t *testing.T) {
	path := filepath.Join(t.TempDir(), "old.db")
	{
		raw, err := sql.Open("sqlite", path)
		if err != nil {
			t.Fatalf("打开旧库失败: %v", err)
		}
		if _, err := raw.Exec(tierTestOldSchemaSQL); err != nil {
			t.Fatalf("建旧表失败: %v", err)
		}
		if _, err := raw.Exec(`INSERT INTO license_code (code, code_hash, type, duration_days, status, created_at)
			VALUES ('OLD1-CODE', 'hash-old-1', 'month', 30, 'active', 1)`); err != nil {
			t.Fatalf("写入旧码失败: %v", err)
		}
		_ = raw.Close()
	}

	db, err := openDB(path)
	if err != nil {
		t.Fatalf("openDB(旧库迁移) 失败: %v", err)
	}
	c, err := getCodeByCode(db, "OLD1-CODE")
	if err != nil || c == nil {
		t.Fatalf("迁移后查询旧码失败: c=%v err=%v", c, err)
	}
	if c.Tier != TierPremium {
		t.Fatalf("存量码迁移后 tier 必须为 3（高级会员），实际 %d", c.Tier)
	}
	// 迁移后新码可指定 1/2/3
	newCode, err := genCode()
	if err != nil {
		t.Fatalf("genCode: %v", err)
	}
	if _, err := insertCodeWithTier(db, newCode, TypeMonth, TierBasic, MonthDurationDays,
		"migration", "test", nowMs()); err != nil {
		t.Fatalf("迁移后写入 tier=1 新码失败: %v", err)
	}
	_ = db.Close()

	// 幂等：重复 openDB（启动 → 停机 → 再启动）不得报错、不得重置已有 tier
	db2, err := openDB(path)
	if err != nil {
		t.Fatalf("重复 openDB（迁移幂等）失败: %v", err)
	}
	defer db2.Close()
	c2, err := getCodeByCode(db2, newCode)
	if err != nil || c2 == nil {
		t.Fatalf("再次打开后查询失败: %v", err)
	}
	if c2.Tier != TierBasic {
		t.Fatalf("幂等迁移不得覆盖已有 tier，期望 1 实际 %d", c2.Tier)
	}
}

// TestTierInsertDefaultAndExplicit：insertCode 默认 3；insertCodeWithTier 显式 1/2/3（含非法值归一化）。
func TestTierInsertDefaultAndExplicit(t *testing.T) {
	db, err := openDB(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatalf("openDB: %v", err)
	}
	defer db.Close()

	cases := []struct {
		name    string
		insTier int
		want    int64
	}{
		{"默认(不传 tier)", TierDefault, TierPremium},
		{"普通", TierBasic, TierBasic},
		{"会员", TierMember, TierMember},
		{"高级会员", TierPremium, TierPremium},
	}
	for _, tc := range cases {
		code, err := genCode()
		if err != nil {
			t.Fatalf("genCode: %v", err)
		}
		if _, err := insertCodeWithTier(db, code, TypeMonth, tc.insTier, MonthDurationDays,
			tc.name, "test", nowMs()); err != nil {
			t.Fatalf("%s: insertCodeWithTier 失败: %v", tc.name, err)
		}
		got, err := getCodeByCode(db, code)
		if err != nil || got == nil {
			t.Fatalf("%s: 查询失败: %v", tc.name, err)
		}
		if got.Tier != tc.want {
			t.Fatalf("%s: tier 落库错误，期望 %d 实际 %d", tc.name, tc.want, got.Tier)
		}
	}

	// 旧兼容入口 insertCode（现有测试/脚本仍在用）默认 3
	legacyCode, err := genCode()
	if err != nil {
		t.Fatalf("genCode: %v", err)
	}
	if _, err := insertCode(db, legacyCode, TypePermanent, 0, "legacy", "test", nowMs()); err != nil {
		t.Fatalf("insertCode 失败: %v", err)
	}
	legacy, err := getCodeByCode(db, legacyCode)
	if err != nil || legacy == nil {
		t.Fatalf("查询 legacy 码失败: %v", err)
	}
	if legacy.Tier != TierPremium {
		t.Fatalf("insertCode 默认 tier 必须为 3（存量兼容），实际 %d", legacy.Tier)
	}
}

// activateAndVerify 走完整两阶段激活 + verify 流程，返回 activate 票据与 verify data。
func activateAndVerify(t *testing.T, srv *Server, code string) (map[string]interface{}, map[string]interface{}) {
	t.Helper()
	h := postSigned(t, srv, opActivate, "/v1/activate", helloFields(), nowMs(),
		tierTestNonce("tier-hello"))
	if envCode(t, h) != CodeOK {
		t.Fatalf("hello 应成功: %v", h)
	}
	hd := envData(t, h)
	salt, _ := hd["salt"].(string)
	cid, _ := hd["challengeId"].(string)
	deviceHash := strings.Repeat("a", 64)

	bind := postSigned(t, srv, opActivate, "/v1/activate", map[string]interface{}{
		"phase":          "bind",
		"code":           code,
		"deviceHash":     deviceHash,
		"deviceLabel":    "tier-test-device",
		"salt":           salt,
		"challengeId":    cid,
		"pkg":            testPkg,
		"appVersion":     "2.3.2",
		"appVersionCode": json.Number("23202"),
	}, nowMs(), tierTestNonce("tier-bind"))
	if envCode(t, bind) != CodeOK {
		t.Fatalf("bind 应成功: %v", bind)
	}
	bindData := envData(t, bind)
	ticket, ok := bindData["ticket"].(map[string]interface{})
	if !ok {
		t.Fatalf("bind 响应缺 ticket: %v", bindData)
	}
	ticketID, _ := ticket["ticketId"].(string)

	verify := postSigned(t, srv, opVerify, "/v1/verify", map[string]interface{}{
		"code":       code,
		"ticketId":   ticketID,
		"deviceHash": deviceHash,
		"pkg":        testPkg,
		"appVersion": "2.3.2",
	}, nowMs(), tierTestNonce("tier-verify"))
	if envCode(t, verify) != CodeOK {
		t.Fatalf("verify 应返回 code:0（业务状态放 data.status）: %v", verify)
	}
	return ticket, envData(t, verify)
}

// TestTierActivateAndVerifyResponse：activate ticket / verify data 均返回 tier（1/2/3 逐一验证）。
func TestTierActivateAndVerifyResponse(t *testing.T) {
	srv, cleanup := newTestEnv(t)
	defer cleanup()

	for _, tier := range []int{TierBasic, TierMember, TierPremium} {
		code, err := genCode()
		if err != nil {
			t.Fatalf("genCode: %v", err)
		}
		if _, err := insertCodeWithTier(srv.db, code, TypeMonth, tier, MonthDurationDays,
			"activate-tier", "test", nowMs()); err != nil {
			t.Fatalf("insertCodeWithTier(%d): %v", tier, err)
		}
		ticket, verifyData := activateAndVerify(t, srv, code)

		gotTicketTier, ok := ticket["tier"].(float64)
		if !ok || int(gotTicketTier) != tier {
			t.Fatalf("activate ticket.tier 期望 %d，实际 %v（ticket=%v）", tier, ticket["tier"], ticket)
		}
		gotVerifyTier, ok := verifyData["tier"].(float64)
		if !ok || int(gotVerifyTier) != tier {
			t.Fatalf("verify data.tier 期望 %d，实际 %v（data=%v）", tier, verifyData["tier"], verifyData)
		}
		if st, _ := verifyData["status"].(string); st != VerifyStatusActive {
			t.Fatalf("verify data.status 期望 active，实际 %q", st)
		}
	}
}

// TestTierLegacyDefaultCodeReturnsPremium：未指定 tier 的旧入口发码 → 端到端返回 3。
func TestTierLegacyDefaultCodeReturnsPremium(t *testing.T) {
	srv, cleanup := newTestEnv(t)
	defer cleanup()

	code, err := genCode()
	if err != nil {
		t.Fatalf("genCode: %v", err)
	}
	if _, err := insertCode(srv.db, code, TypeMonth, MonthDurationDays, "legacy", "test", nowMs()); err != nil {
		t.Fatalf("insertCode: %v", err)
	}
	ticket, verifyData := activateAndVerify(t, srv, code)
	if got, _ := ticket["tier"].(float64); int(got) != TierPremium {
		t.Fatalf("旧入口码 ticket.tier 必须为 3，实际 %v", ticket["tier"])
	}
	if got, _ := verifyData["tier"].(float64); int(got) != TierPremium {
		t.Fatalf("旧入口码 verify tier 必须为 3，实际 %v", verifyData["tier"])
	}
}

// TestTierJSONShape：Ticket / VerifyData 的 JSON 增加 tier 字段，且不破坏既有字段。
func TestTierJSONShape(t *testing.T) {
	b, err := json.Marshal(Ticket{TicketID: "t", Code: "c", Type: TypeMonth, Tier: TierMember,
		ActivatedAt: 1, ExpireAt: 2, ServerStatus: StatusActive})
	if err != nil {
		t.Fatalf("marshal Ticket: %v", err)
	}
	s := string(b)
	if !strings.Contains(s, `"tier":2`) {
		t.Fatalf("Ticket JSON 必须含 tier，got=%s", s)
	}
	for _, key := range []string{`"ticketId"`, `"code"`, `"type"`, `"activatedAt"`, `"expireAt"`, `"serverStatus"`} {
		if !strings.Contains(s, key) {
			t.Fatalf("Ticket JSON 不得丢失既有字段 %s，got=%s", key, s)
		}
	}

	v, err := json.Marshal(VerifyData{Status: VerifyStatusActive, Type: TypeMonth, Tier: TierBasic,
		ExpireAt: 2, RemainSeconds: 3, DeviceLabel: "d"})
	if err != nil {
		t.Fatalf("marshal VerifyData: %v", err)
	}
	if !strings.Contains(string(v), `"tier":1`) {
		t.Fatalf("VerifyData JSON 必须含 tier，got=%s", v)
	}
	// 未激活分支仍不带 status（model_test.go 的封闭集合回归在此一并复核）
	notActivated, err := json.Marshal(VerifyData{Type: TypeMonth, Tier: TierPremium})
	if err != nil {
		t.Fatalf("marshal VerifyData(未激活): %v", err)
	}
	if strings.Contains(string(notActivated), `"status"`) {
		t.Fatalf("未激活响应不得含 status，got=%s", notActivated)
	}
	if !strings.Contains(string(notActivated), `"tier":3`) {
		t.Fatalf("未激活响应仍应返回 tier，got=%s", notActivated)
	}
}

// TestTierCLIIssueWithTierFlag：`issue --tier 1` 落库 tier=1 且 CSV 导出含 tier 列。
func TestTierCLIIssueWithTierFlag(t *testing.T) {
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "cli.db")
	csvPath := filepath.Join(dir, "codes.csv")

	if err := cmdIssue([]string{
		"--db", dbPath,
		"--type", TypeMonth,
		"--tier", "1",
		"--count", "2",
		"--remark", "tier-cli",
		"--out", csvPath,
	}); err != nil {
		t.Fatalf("cmdIssue --tier 1 失败: %v", err)
	}

	db, err := openDB(dbPath)
	if err != nil {
		t.Fatalf("openDB: %v", err)
	}
	defer db.Close()
	rows, err := db.Query(`SELECT code, tier FROM license_code ORDER BY id`)
	if err != nil {
		t.Fatalf("查询失败: %v", err)
	}
	defer rows.Close()
	n := 0
	for rows.Next() {
		var code string
		var tier int64
		if err := rows.Scan(&code, &tier); err != nil {
			t.Fatalf("scan: %v", err)
		}
		if tier != TierBasic {
			t.Fatalf("CLI --tier 1 落库期望 1，实际 %d（code=%s）", tier, code)
		}
		n++
	}
	if n != 2 {
		t.Fatalf("期望 2 条码，实际 %d", n)
	}

	csvBytes, err := os.ReadFile(csvPath)
	if err != nil {
		t.Fatalf("读取 CSV 失败: %v", err)
	}
	csvText := string(csvBytes)
	if !strings.Contains(csvText, "tier") || !strings.Contains(csvText, "普通") {
		t.Fatalf("CSV 导出必须含 tier 列与等级名，got=\n%s", csvText)
	}
}

// TestTierCLIIssueRejectsInvalidTier：`--tier 9` 必须报错且不建码。
func TestTierCLIIssueRejectsInvalidTier(t *testing.T) {
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "cli.db")
	if err := cmdIssue([]string{"--db", dbPath, "--tier", "9", "--count", "1"}); err == nil {
		t.Fatal("--tier 9 应返回错误")
	}
}
