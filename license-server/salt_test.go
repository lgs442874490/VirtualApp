package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

// ⚠️ 说明（务必先读）：
// 服务端只做「存储 + 比对」，收到的 deviceHash 是客户端算的，故**服务端侧无法复现**
// 「重装后指纹漂移 → 1003」这一真机缺陷（真机复现点见 QA）。本文件的测试只断言
// **指纹盐的确定性** 与 **challengeId 一次性消费未被破坏**，不声称验证了幂等修复本身。

const testPkg = "com.carlos.multiapp"

// newTestEnv 构造一个使用临时库的 Server 及清理函数。
func newTestEnv(t *testing.T) (*Server, func()) {
	t.Helper()
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "t.db")
	db, err := openDB(dbPath)
	if err != nil {
		t.Fatalf("openDB: %v", err)
	}
	cfg := &Config{
		DBPath:     dbPath,
		Secret:     "unit-test-secret-0123456789abcdef",
		AdminToken: "unit-admin",
		Addr:       "127.0.0.1:0",
		RebindMax:  RebindMaxDefault,
		NonceTTLMs: NonceTTLDefaultSec * 1000,
	}
	return NewServer(db, cfg), func() { db.Close() }
}

// postSigned 以给定字段构造请求、按冻结口径签名，并发往 srv 的 mux，返回响应信封。
// fields 会被就地修改（注入 ts/nonce/sign），调用方每次应传新 map。
func postSigned(t *testing.T, srv *Server, op, path string, fields map[string]interface{}, ts int64, nonce string) map[string]interface{} {
	t.Helper()
	fields["ts"] = json.Number(strconv.FormatInt(ts, 10))
	fields["nonce"] = nonce
	canon, err := canonicalizeMap(fields)
	if err != nil {
		t.Fatalf("canonicalizeMap: %v", err)
	}
	fields["sign"] = computeSign(srv.cfg.Secret, "POST", op, ts, nonce, canon)
	raw, err := json.Marshal(fields)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	srv.Mux().ServeHTTP(rec, req)
	var env map[string]interface{}
	if err := json.Unmarshal(rec.Body.Bytes(), &env); err != nil {
		t.Fatalf("解析响应失败: %v, body=%s", err, rec.Body.String())
	}
	return env
}

// envCode 取响应信封 code。
func envCode(t *testing.T, env map[string]interface{}) int {
	t.Helper()
	c, ok := env["code"].(float64)
	if !ok {
		t.Fatalf("响应缺 code: %v", env)
	}
	return int(c)
}

// envData 取响应信封 data。
func envData(t *testing.T, env map[string]interface{}) map[string]interface{} {
	t.Helper()
	d, ok := env["data"].(map[string]interface{})
	if !ok {
		t.Fatalf("响应缺 data: %v", env)
	}
	return d
}

func helloFields() map[string]interface{} {
	return map[string]interface{}{
		"phase":          "hello",
		"pkg":            testPkg,
		"appVersion":     "2.3.2",
		"appVersionCode": json.Number("23202"),
	}
}

// TestHelloSaltDeterministic（第 1 条）——连续两次 hello 返回的 salt 必须完全相同。
// 这是「确定性指纹盐」修复的直接证据；同时 challengeId 必须每次不同（保持一次性）。
func TestHelloSaltDeterministic(t *testing.T) {
	srv, cleanup := newTestEnv(t)
	defer cleanup()

	h1 := postSigned(t, srv, opActivate, "/v1/activate", helloFields(), nowMs(), "n0000000000000000000000000000001")
	h2 := postSigned(t, srv, opActivate, "/v1/activate", helloFields(), nowMs(), "n0000000000000000000000000000002")
	if envCode(t, h1) != CodeOK || envCode(t, h2) != CodeOK {
		t.Fatalf("hello 应成功: h1=%v h2=%v", h1, h2)
	}
	d1, d2 := envData(t, h1), envData(t, h2)
	salt1, _ := d1["salt"].(string)
	salt2, _ := d2["salt"].(string)

	if salt1 == "" || salt2 == "" {
		t.Fatalf("salt 为空: %q %q", salt1, salt2)
	}
	if salt1 != salt2 {
		t.Fatalf("两次 hello 的 salt 必须相同（确定性派生）\n h1=%s\n h2=%s", salt1, salt2)
	}
	if len(salt1) != 32 {
		t.Fatalf("salt 长度应为 32 位 hex，实际 %d: %s", len(salt1), salt1)
	}
	if salt1 != fingerprintSalt(srv.cfg.Secret) {
		t.Fatalf("hello salt 应等于 fingerprintSalt(secret): got=%s want=%s", salt1, fingerprintSalt(srv.cfg.Secret))
	}
	cid1, _ := d1["challengeId"].(string)
	cid2, _ := d2["challengeId"].(string)
	if cid1 == "" || cid1 == cid2 {
		t.Fatalf("challengeId 每次必须不同（保持一次性）: %q vs %q", cid1, cid2)
	}
	t.Logf("salt(两次相同)=%s  cid1=%s  cid2=%s", salt1, cid1, cid2)
}

// TestFingerprintSaltDeterministic（第 2 条）——同 secret 稳定、异 secret 不同。
func TestFingerprintSaltDeterministic(t *testing.T) {
	a1, a2 := fingerprintSalt("A"), fingerprintSalt("A")
	b := fingerprintSalt("B")
	if a1 != a2 {
		t.Fatalf("同 secret 两次派生应相同: %s vs %s", a1, a2)
	}
	if a1 == b {
		t.Fatalf("异 secret 应派生不同盐: A=%s B=%s", a1, b)
	}
	if len(a1) != 32 {
		t.Fatalf("salt 长度应为 32: %d", len(a1))
	}
	t.Logf("salt(A)=%s salt(B)=%s", a1, b)
}

// TestChallengeIdSingleUse（第 4 条）——确认 fingerprintSalt 改造**未破坏** challengeId
// 的一次性消费：同一个 cid 用两次，第二次必须失败（1009）。
func TestChallengeIdSingleUse(t *testing.T) {
	srv, cleanup := newTestEnv(t)
	defer cleanup()

	code, err := genCode()
	if err != nil {
		t.Fatalf("genCode: %v", err)
	}
	if _, err := insertCode(srv.db, code, TypeMonth, MonthDurationDays, "unit", "unit", nowMs()); err != nil {
		t.Fatalf("insertCode: %v", err)
	}

	h := postSigned(t, srv, opActivate, "/v1/activate", helloFields(), nowMs(), "n000000000000000000000000000000a")
	if envCode(t, h) != CodeOK {
		t.Fatalf("hello 应成功: %v", h)
	}
	hd := envData(t, h)
	salt, _ := hd["salt"].(string)
	cid, _ := hd["challengeId"].(string)

	dh := strings.Repeat("a", 64)
	bindFields := func() map[string]interface{} {
		return map[string]interface{}{
			"phase":          "bind",
			"code":           code,
			"deviceHash":     dh,
			"deviceLabel":    "unit-device",
			"salt":           salt,
			"challengeId":    cid,
			"pkg":            testPkg,
			"appVersion":     "2.3.2",
			"appVersionCode": json.Number("23202"),
		}
	}

	b1 := postSigned(t, srv, opActivate, "/v1/activate", bindFields(), nowMs(), "n000000000000000000000000000000b")
	if envCode(t, b1) != CodeOK {
		t.Fatalf("bind#1 应成功(code:0)，实际 %v", b1)
	}
	b2 := postSigned(t, srv, opActivate, "/v1/activate", bindFields(), nowMs(), "n000000000000000000000000000000c")
	if envCode(t, b2) != CodeChallengeInvalid {
		t.Fatalf("bind#2 复用同一 cid 应失败(%d/1009)，实际 %v", CodeChallengeInvalid, b2)
	}
	t.Logf("bind#1 code=%d;  bind#2(复用 cid) code=%d (期望 %d)", envCode(t, b1), envCode(t, b2), CodeChallengeInvalid)
}
