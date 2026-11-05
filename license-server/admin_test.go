package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

// ---------------------------------------------------------------------------
// 2026-11-06 管理后台 / 功能矩阵 / 角色下发 单测
// ---------------------------------------------------------------------------

const testAdminPassword = "test-admin-password-2026"

func newTestServer(t *testing.T, withPassword bool) (*Server, *httptest.Server) {
	t.Helper()
	dbPath := filepath.Join(t.TempDir(), "license-test.db")
	db, err := openDB(dbPath)
	if err != nil {
		t.Fatalf("openDB: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })
	cfg := &Config{
		DBPath:     dbPath,
		Secret:     "unit-test-secret-0123456789abcdef",
		AdminToken: "unit-admin",
		Addr:       "127.0.0.1:0",
		NonceTTLMs: NonceTTLDefaultSec * 1000,
	}
	if withPassword {
		cfg.AdminPassword = testAdminPassword
	}
	srv := NewServer(db, cfg)
	ts := httptest.NewServer(srv.Mux())
	t.Cleanup(ts.Close)
	return srv, ts
}

// ---------- 功能目录与默认矩阵 ----------

func TestFeatureSeedMatchesApp(t *testing.T) {
	if len(featureSeed) != 39 {
		t.Fatalf("功能目录应为 39 项（含 proto_capture），实际 %d", len(featureSeed))
	}
	seen := map[string]bool{}
	protoCapture := false
	for _, it := range featureSeed {
		if seen[it.Key] {
			t.Fatalf("功能 key 重复: %s", it.Key)
		}
		seen[it.Key] = true
		if it.Key == "proto_capture" {
			protoCapture = true
			if !it.Implemented || it.DebugOnly || it.DefaultTier != TierPremium {
				t.Fatalf("proto_capture 应为 T3 已实现非调试项")
			}
		}
	}
	if !protoCapture {
		t.Fatal("缺少 proto_capture（App FeatureGate 已含）")
	}
}

func TestDefaultMatrixCounts(t *testing.T) {
	srv, _ := newTestServer(t, true)
	m, err := loadMatrix(srv.db)
	if err != nil {
		t.Fatalf("loadMatrix: %v", err)
	}
	count := func(role int) int {
		n := 0
		for _, v := range m[role] {
			if v {
				n++
			}
		}
		return n
	}
	// T1=5；T2=T1+9+巨兽=15；T3=已实现全集=21。
	if got := count(1); got != 5 {
		t.Fatalf("T1 默认应为 5 项，实际 %d", got)
	}
	if got := count(2); got != 15 {
		t.Fatalf("T2 默认应为 15 项，实际 %d", got)
	}
	if got := count(3); got != 21 {
		t.Fatalf("T3 默认应为 21 项，实际 %d", got)
	}
}

func TestFeaturesForRole(t *testing.T) {
	srv, _ := newTestServer(t, true)
	t1, err := featuresForRole(srv.db, 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(t1) != 5 || t1[0] != "account_add" {
		t.Fatalf("T1 features 不符: %v", t1)
	}
	gm, err := featuresForRole(srv.db, RoleGM)
	if err != nil {
		t.Fatal(err)
	}
	if len(gm) != 21 {
		t.Fatalf("GM 应为 21 项已实现功能，实际 %d: %v", len(gm), gm)
	}
	for _, k := range gm {
		if k == "move_city" || k == "ranking" || k == "protocol_logger" || k == "protocol_probe" {
			t.Fatalf("GM 不应包含未实现/调试项: %s", k)
		}
	}
}

func TestSaveMatrixValidation(t *testing.T) {
	srv, _ := newTestServer(t, true)
	// 构造完整矩阵，然后把某个未实现项打开 → 拒绝。
	rows := make([]MatrixCell, 0, len(featureSeed)*3)
	for _, it := range featureSeed {
		for _, role := range []int{1, 2, 3} {
			rows = append(rows, MatrixCell{Key: it.Key, Role: role, Visible: defaultVisible(role, it)})
		}
	}
	bad := append([]MatrixCell{}, rows...)
	for i := range bad {
		if bad[i].Key == "move_city" && bad[i].Role == 3 {
			bad[i].Visible = true
		}
	}
	if _, err := saveMatrix(srv.db, bad); err == nil {
		t.Fatal("未实现项被打开应报错")
	}
	// 缺行 → 拒绝。
	if _, err := saveMatrix(srv.db, rows[:len(rows)-1]); err == nil {
		t.Fatal("矩阵不完整应报错")
	}
	// 未知 key → 拒绝。
	unknown := append([]MatrixCell{{Key: "nope", Role: 1, Visible: true}}, rows...)
	if _, err := saveMatrix(srv.db, unknown); err == nil {
		t.Fatal("未知 key 应报错")
	}
}

func TestSaveAndResetMatrix(t *testing.T) {
	srv, _ := newTestServer(t, true)
	rows := make([]MatrixCell, 0, len(featureSeed)*3)
	for _, it := range featureSeed {
		for _, role := range []int{1, 2, 3} {
			v := defaultVisible(role, it)
			if it.Key == "today_tab" && role == 2 {
				v = false // 取消 T2 的今日页
			}
			rows = append(rows, MatrixCell{Key: it.Key, Role: role, Visible: v})
		}
	}
	diffs, err := saveMatrix(srv.db, rows)
	if err != nil {
		t.Fatalf("saveMatrix: %v", err)
	}
	if len(diffs) != 1 || diffs[0].Key != "today_tab" || diffs[0].Role != 2 {
		t.Fatalf("diff 不符: %+v", diffs)
	}
	t2, _ := featuresForRole(srv.db, 2)
	for _, k := range t2 {
		if k == "today_tab" {
			t.Fatal("保存后 T2 不应含 today_tab")
		}
	}
	if _, err := resetMatrix(srv.db); err != nil {
		t.Fatalf("resetMatrix: %v", err)
	}
	t2, _ = featuresForRole(srv.db, 2)
	found := false
	for _, k := range t2 {
		if k == "today_tab" {
			found = true
		}
	}
	if !found {
		t.Fatal("恢复默认后 T2 应重新含 today_tab")
	}
}

// ---------- 管理 API（HTTP） ----------

func doJSON(t *testing.T, method, url, body, cookie string) (*http.Response, map[string]interface{}) {
	t.Helper()
	var rdr io.Reader
	if body != "" {
		rdr = strings.NewReader(body)
	}
	req, err := http.NewRequest(method, url, rdr)
	if err != nil {
		t.Fatal(err)
	}
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	if cookie != "" {
		req.Header.Set("Cookie", cookie)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var m map[string]interface{}
	_ = json.Unmarshal(raw, &m)
	return resp, m
}

func loginCookie(t *testing.T, ts *httptest.Server) string {
	t.Helper()
	resp, _ := doJSON(t, "POST", ts.URL+"/admin/api/login",
		`{"password":"`+testAdminPassword+`"}`, "")
	if resp.StatusCode != 200 {
		t.Fatalf("登录应成功，实际 %d", resp.StatusCode)
	}
	for _, c := range resp.Cookies() {
		if c.Name == adminCookieName {
			return adminCookieName + "=" + c.Value
		}
	}
	t.Fatal("登录未下发会话 cookie")
	return ""
}

func TestAdminUnconfigured(t *testing.T) {
	_, ts := newTestServer(t, false)
	resp, _ := doJSON(t, "GET", ts.URL+"/admin/api/me", "", "")
	if resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("未配置口令应 503，实际 %d", resp.StatusCode)
	}
	resp, _ = doJSON(t, "GET", ts.URL+"/admin", "", "")
	if resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("未配置口令页面应 503，实际 %d", resp.StatusCode)
	}
}

func TestAdminLoginLockout(t *testing.T) {
	_, ts := newTestServer(t, true)
	for i := 0; i < adminMaxFails; i++ {
		resp, _ := doJSON(t, "POST", ts.URL+"/admin/api/login", `{"password":"wrong"}`, "")
		if resp.StatusCode != http.StatusUnauthorized {
			t.Fatalf("第 %d 次错误口令应 401，实际 %d", i+1, resp.StatusCode)
		}
	}
	resp, _ := doJSON(t, "POST", ts.URL+"/admin/api/login", `{"password":"wrong"}`, "")
	if resp.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("连续失败应锁定 429，实际 %d", resp.StatusCode)
	}
}

func TestAdminGenerateListRevokeRestore(t *testing.T) {
	_, ts := newTestServer(t, true)
	cookie := loginCookie(t, ts)

	resp, gen := doJSON(t, "POST", ts.URL+"/admin/api/codes/generate",
		`{"count":3,"type":"month","tier":"2","remark":"单元测试"}`, cookie)
	if resp.StatusCode != 200 || gen["code"].(float64) != 0 {
		t.Fatalf("生成失败: %v", gen)
	}
	codes := gen["data"].(map[string]interface{})["codes"].([]interface{})
	if len(codes) != 3 {
		t.Fatalf("应生成 3 条，实际 %d", len(codes))
	}
	first := codes[0].(map[string]interface{})
	id := int64(first["id"].(float64))
	if first["usage"] != "unused" || first["role"].(float64) != 2 {
		t.Fatalf("新码状态/角色不符: %v", first)
	}

	// 列表筛选：等级=2 且使用状态=未使用。
	_, list := doJSON(t, "GET", ts.URL+"/admin/api/codes?tier=2&usage=unused", "", cookie)
	if total := list["data"].(map[string]interface{})["total"].(float64); total < 3 {
		t.Fatalf("筛选应至少 3 条，实际 %v", total)
	}

	// 停用 → 使用状态=已停用。
	if resp, _ := doJSON(t, "POST", fmt.Sprintf("%s/admin/api/codes/%d/revoke", ts.URL, id),
		`{"reason":"单元测试停用"}`, cookie); resp.StatusCode != 200 {
		t.Fatalf("停用失败 %d", resp.StatusCode)
	}
	_, detail := doJSON(t, "GET", fmt.Sprintf("%s/admin/api/codes/%d", ts.URL, id), "", cookie)
	codeObj := detail["data"].(map[string]interface{})["code"].(map[string]interface{})
	if codeObj["usage"] != "revoked" {
		t.Fatalf("停用后应为 revoked，实际 %v", codeObj["usage"])
	}

	// 启用 → 恢复 active（未使用）。
	if resp, _ := doJSON(t, "POST", fmt.Sprintf("%s/admin/api/codes/%d/restore", ts.URL, id),
		"", cookie); resp.StatusCode != 200 {
		t.Fatalf("启用失败 %d", resp.StatusCode)
	}
	_, detail = doJSON(t, "GET", fmt.Sprintf("%s/admin/api/codes/%d", ts.URL, id), "", cookie)
	codeObj = detail["data"].(map[string]interface{})["code"].(map[string]interface{})
	if codeObj["usage"] != "unused" {
		t.Fatalf("启用后应为 unused，实际 %v", codeObj["usage"])
	}

	// 审计应包含 generate/revoke/restore（login 也有）。
	_, audit := doJSON(t, "GET", ts.URL+"/admin/api/audit", "", cookie)
	items := audit["data"].(map[string]interface{})["items"].([]interface{})
	if len(items) < 3 {
		t.Fatalf("审计条数不足: %d", len(items))
	}
}

func TestAdminMatrixHTTP(t *testing.T) {
	_, ts := newTestServer(t, true)
	cookie := loginCookie(t, ts)

	_, got := doJSON(t, "GET", ts.URL+"/admin/api/matrix", "", cookie)
	rows := got["data"].(map[string]interface{})["rows"].([]interface{})
	if len(rows) != 39 {
		t.Fatalf("矩阵应有 39 行，实际 %d", len(rows))
	}

	// 构造全量 T1/T2/T3 提交，取消除 T2 的 today_tab。
	var cells []map[string]interface{}
	for _, r := range rows {
		row := r.(map[string]interface{})
		key := row["key"].(string)
		for _, role := range []int{1, 2, 3} {
			visible := row[fmt.Sprintf("t%d", role)].(bool)
			if key == "today_tab" && role == 2 {
				visible = false
			}
			cells = append(cells, map[string]interface{}{"key": key, "role": role, "visible": visible})
		}
	}
	body, _ := json.Marshal(map[string]interface{}{"rows": cells})
	resp, put := doJSON(t, "PUT", ts.URL+"/admin/api/matrix", string(body), cookie)
	if resp.StatusCode != 200 || put["code"].(float64) != 0 {
		t.Fatalf("保存矩阵失败: %v", put)
	}

	// 通过矩阵接口验证 T2 已不含 today_tab。
	_, got2 := doJSON(t, "GET", ts.URL+"/admin/api/matrix", "", cookie)
	for _, r := range got2["data"].(map[string]interface{})["rows"].([]interface{}) {
		row := r.(map[string]interface{})
		if row["key"] == "today_tab" && row["t2"].(bool) {
			t.Fatal("矩阵保存后 T2 不应含 today_tab")
		}
	}

	// 恢复默认。
	if resp, _ := doJSON(t, "POST", ts.URL+"/admin/api/matrix/reset", "", cookie); resp.StatusCode != 200 {
		t.Fatalf("恢复默认失败 %d", resp.StatusCode)
	}
}

// ---------- 授权接口：role/features 下发 ----------

type signedClient struct {
	t      *testing.T
	base   string
	secret string
	nonce  int
}

func (c *signedClient) post(op, path string, body map[string]interface{}) map[string]interface{} {
	c.t.Helper()
	ts := nowMs()
	c.nonce++
	m := map[string]interface{}{}
	for k, v := range body {
		m[k] = v
	}
	m["ts"] = ts
	m["nonce"] = fmt.Sprintf("%032x", c.nonce)
	raw, _ := json.Marshal(m)
	canonical, err := canonicalizeRaw(raw)
	if err != nil {
		c.t.Fatal(err)
	}
	sign := computeSign(c.secret, "POST", op, ts, m["nonce"].(string), canonical)
	m["sign"] = sign
	raw, _ = json.Marshal(m)
	resp, err := http.Post(c.base+path, "application/json", bytes.NewReader(raw))
	if err != nil {
		c.t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	var out map[string]interface{}
	_ = json.Unmarshal(b, &out)
	return out
}

func TestActivateVerifyRoleFeatures(t *testing.T) {
	srv, ts := newTestServer(t, true)
	// 直接插一条 T2 月码与一条 GM 永久码（避免走管理 API 的 HTTP 依赖）。
	t2Code := mustInsertCode(t, srv, 2, false, TypeMonth)
	gmCode := mustInsertCode(t, srv, 3, true, TypePermanent)

	cli := &signedClient{t: t, base: ts.URL, secret: srv.cfg.Secret}
	// 1) T2 激活：role=2，features 含 today_tab、不含 enemy_watch。
	hello := cli.post("activate", "/v1/activate", map[string]interface{}{
		"phase": "hello", "pkg": "com.carlos.multiapp", "appVersion": "4.2.58", "appVersionCode": 23600,
	})
	if hello["code"].(float64) != 0 {
		t.Fatalf("hello 失败: %v", hello)
	}
	hdata := hello["data"].(map[string]interface{})
	salt := hdata["salt"].(string)
	cid := hdata["challengeId"].(string)

	bind := cli.post("activate", "/v1/activate", map[string]interface{}{
		"phase": "bind", "code": t2Code, "deviceHash": strings.Repeat("a", 64),
		"deviceLabel": "unit-test", "salt": salt, "challengeId": cid,
		"pkg": "com.carlos.multiapp", "appVersion": "4.2.58", "appVersionCode": 23600,
	})
	if bind["code"].(float64) != 0 {
		t.Fatalf("bind 失败: %v", bind)
	}
	ticket := bind["data"].(map[string]interface{})["ticket"].(map[string]interface{})
	if ticket["role"].(float64) != 2 {
		t.Fatalf("T2 码 role 应为 2: %v", ticket["role"])
	}
	if !containsStr(ticket["features"], "today_tab") || containsStr(ticket["features"], "enemy_watch") {
		t.Fatalf("T2 features 不符: %v", ticket["features"])
	}
	ticketID := ticket["ticketId"].(string)

	// 2) verify 下发 role/features。
	verify := cli.post("verify", "/v1/verify", map[string]interface{}{
		"code": t2Code, "ticketId": ticketID, "deviceHash": strings.Repeat("a", 64),
		"pkg": "com.carlos.multiapp", "appVersion": "4.2.58",
	})
	vdata := verify["data"].(map[string]interface{})
	if verify["code"].(float64) != 0 || vdata["role"].(float64) != 2 {
		t.Fatalf("verify 应 role=2: %v", verify)
	}
	if !containsStr(vdata["features"], "today_tab") {
		t.Fatalf("verify features 不符: %v", vdata["features"])
	}

	// 3) GM 永久码：role=4，features 为已实现全集（21），不含未实现项。
	hello2 := cli.post("activate", "/v1/activate", map[string]interface{}{
		"phase": "hello", "pkg": "com.carlos.multiapp", "appVersion": "4.2.58", "appVersionCode": 23600,
	})
	hdata2 := hello2["data"].(map[string]interface{})
	bind2 := cli.post("activate", "/v1/activate", map[string]interface{}{
		"phase": "bind", "code": gmCode, "deviceHash": strings.Repeat("b", 64),
		"deviceLabel": "unit-gm", "salt": hdata2["salt"].(string), "challengeId": hdata2["challengeId"].(string),
		"pkg": "com.carlos.multiapp", "appVersion": "4.2.58", "appVersionCode": 23600,
	})
	if bind2["code"].(float64) != 0 {
		t.Fatalf("GM bind 失败: %v", bind2)
	}
	gt := bind2["data"].(map[string]interface{})["ticket"].(map[string]interface{})
	if gt["role"].(float64) != 4 || gt["tier"].(float64) != 3 {
		t.Fatalf("GM 应 role=4/tier=3，实际 %v/%v", gt["role"], gt["tier"])
	}
	if !containsStr(gt["features"], "enemy_watch") || containsStr(gt["features"], "move_city") {
		t.Fatalf("GM features 不符: %v", gt["features"])
	}
}

func mustInsertCode(t *testing.T, srv *Server, tier int, gm bool, typ string) string {
	t.Helper()
	code, err := genUniqueCode(srv.db)
	if err != nil {
		t.Fatal(err)
	}
	duration := int64(0)
	if typ == TypeMonth {
		duration = MonthDurationDays
	}
	if _, err := insertCodeWithRole(srv.db, code, typ, tier, gm, duration, "unit-test", "unit", nowMs()); err != nil {
		t.Fatal(err)
	}
	return code
}

func containsStr(v interface{}, want string) bool {
	arr, ok := v.([]interface{})
	if !ok {
		return false
	}
	for _, x := range arr {
		if s, ok := x.(string); ok && s == want {
			return true
		}
	}
	return false
}

// TestListLogsAndAudit 使用记录/审计的查询与筛选。
func TestListLogsAndAudit(t *testing.T) {
	srv, _ := newTestServer(t, true)
	code := mustInsertCode(t, srv, 2, false, TypeMonth)
	c, err := getCodeByCode(srv.db, code)
	if err != nil || c == nil {
		t.Fatalf("查码失败: %v", err)
	}
	now := nowMs()
	insertLog(srv.db, codeIDPtr(c.ID), "dev-a", ActionActivate, CodeOK, "1.2.3.4", "ua", now-3000)
	insertLog(srv.db, codeIDPtr(c.ID), "dev-a", ActionVerify, CodeOK, "1.2.3.4", "ua", now-2000)
	insertLog(srv.db, codeIDPtr(c.ID), "dev-a", ActionVerify, CodeExpired, "1.2.3.4", "ua", now-1000)

	items, total, err := listLogs(srv.db, LogFilter{CodeID: c.ID, Page: 1, Size: 20})
	if err != nil {
		t.Fatal(err)
	}
	if total != 3 || len(items) != 3 {
		t.Fatalf("应 3 条记录，实际 total=%d len=%d", total, len(items))
	}
	if items[0].ResultCode != CodeExpired || items[0].ResultText == "" {
		t.Fatalf("应按时间倒序且带结果文案: %+v", items[0])
	}

	// 按动作筛选。
	_, total, _ = listLogs(srv.db, LogFilter{CodeID: c.ID, Action: ActionVerify, Page: 1, Size: 20})
	if total != 2 {
		t.Fatalf("verify 应 2 条，实际 %d", total)
	}
	// 按结果筛选。
	_, total, _ = listLogs(srv.db, LogFilter{CodeID: c.ID, Result: "fail", Page: 1, Size: 20})
	if total != 1 {
		t.Fatalf("失败记录应 1 条，实际 %d", total)
	}

	// 审计写入/查询。
	insertAdminAudit(srv.db, "admin", AdminActionMatrixSave, "role_feature", "", `{"changes":1}`, "1.2.3.4")
	audits, atotal, err := listAudit(srv.db, 1, 20)
	if err != nil {
		t.Fatal(err)
	}
	if atotal < 1 || len(audits) < 1 || audits[0].Action != AdminActionMatrixSave {
		t.Fatalf("审计查询不符: total=%d items=%+v", atotal, audits)
	}
}
