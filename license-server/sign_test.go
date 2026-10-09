package main

import (
	"strings"
	"testing"
)

// withSign 把「去掉 sign 后的 canonical」还原成「带 sign 的完整请求体」（sign 值无关紧要，
// canonicalizeRaw 会剔除它）。对象键顺序对解析无影响，输出会被重新规范化。
func withSign(canonical string) string {
	return `{"sign":"placeholder",` + canonical[1:]
}

// TestCanonicalizeRaw 验证规范化 JSON 的核心规则：键排序、去空白、数字原样、
// 数组保序、中文/特殊字符不转义。
func TestCanonicalizeRaw(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want string
	}{
		{
			name: "键排序与去空白",
			in:   `{ "ts": 1712345678901, "nonce": "abc", "code": "K7M9-PQRS-TUVW-XYZL" }`,
			want: `{"code":"K7M9-PQRS-TUVW-XYZL","nonce":"abc","ts":1712345678901}`,
		},
		{
			name: "嵌套对象与数组保序",
			in:   `{"a":[1,true,null,"x"],"b":{"z":2,"a":"中文"}}`,
			want: `{"a":[1,true,null,"x"],"b":{"a":"中文","z":2}}`,
		},
		{
			name: "字符串转义（<>& 与 / 不转义）",
			in:   `{"s":"a\"b\\c\nd\te/f<g>&h"}`,
			want: "{\"s\":\"a\\\"b\\\\c\\nd\\te/f<g>&h\"}",
		},
	}
	for _, tc := range cases {
		got, err := canonicalizeRaw([]byte(tc.in))
		if err != nil {
			t.Fatalf("[%s] canonicalizeRaw 出错: %v", tc.name, err)
		}
		if string(got) != tc.want {
			t.Fatalf("[%s] 规范化结果不符\n got: %s\nwant: %s", tc.name, got, tc.want)
		}
	}
}

// TestCodeChecksum 验证发码与校验位自洽。
func TestCodeChecksum(t *testing.T) {
	for i := 0; i < 1000; i++ {
		code, err := genCode()
		if err != nil {
			t.Fatalf("genCode 出错: %v", err)
		}
		if !validateCode(code) {
			t.Fatalf("生成的码未通过自校验: %s", code)
		}
		if canonicalCode(code) != code {
			t.Fatalf("规范化改变了生成的码: %s -> %s", code, canonicalCode(code))
		}
	}
	code, _ := genCode()
	raw := []byte(code)
	if raw[len(raw)-1] != 'A' {
		raw[len(raw)-1] = 'A'
	} else {
		raw[len(raw)-1] = 'B'
	}
	if validateCode(string(raw)) {
		t.Fatalf("被篡改的码不应通过校验: %s", string(raw))
	}
}

// ---------------------------------------------------------------------------
// 架构师冻结的黄金测试向量（license-signature-testvectors.md）
//
// 冻结修订：签名串第 3 槽为 OP（activate/verify/rebind），**不含 /v1 前缀**。
// 用独立 Python + 真实 Java 实现双算并逐字节比对，故为权威锚点。
// 若本测试失败，说明服务端 sign 与客户端不一致 → 线上将「签名全线 2001」。
// ---------------------------------------------------------------------------

const (
	frozenSecret = "vc2026-hj-license-9f3a7c1e5b8d2460"
	frozenTS     = int64(1757844000000)
	frozenNonce  = "0123456789abcdef0123456789abcdef"
)

// TestFrozenGoldenVectors 逐字节断言 canonical / SHA256 / sign 三者。
func TestFrozenGoldenVectors(t *testing.T) {
	hash64 := strings.Repeat("a", 64) // deviceHash：64 个 a
	salt32 := strings.Repeat("b", 32) // salt：32 个 b

	vectors := []struct {
		name      string
		op        string
		canonical string
		wantSHA   string
		wantSign  string
	}{
		{
			name: "activate phase=hello",
			op:   opActivate,
			canonical: `{"appVersion":"2.3.2","appVersionCode":23202,"nonce":"` + frozenNonce +
				`","phase":"hello","pkg":"com.carlos.multiapp","ts":1757844000000}`,
			wantSHA:  "0271bbc23d680d85fca6fffc7422125904fe39a7374cde737e5c076e10d60d11",
			wantSign: "c245e9280564bfe4e67079ae2d9eec1f5eba147257cab691c363c6ae561ae319",
		},
		{
			name: "activate phase=bind",
			op:   opActivate,
			canonical: `{"appVersion":"2.3.2","appVersionCode":23202,"challengeId":"9f3a7c1e5b8d2460",` +
				`"code":"K7M9-PQRS-TUVW-XYZL","deviceHash":"` + hash64 + `","deviceLabel":"vivo V2141A",` +
				`"nonce":"` + frozenNonce + `","phase":"bind","pkg":"com.carlos.multiapp","salt":"` + salt32 +
				`","ts":1757844000000}`,
			wantSHA:  "6109e54289a78209e5aab99a0ff1a1228ae460d9acbd1a57d0b62bb760da0c7e",
			wantSign: "9debd9c6c260baf16f63674f7b6880691994a0e62055b12cc6ff6c625c166d85",
		},
		{
			name: "verify",
			op:   opVerify,
			canonical: `{"appVersion":"2.3.2","code":"K7M9-PQRS-TUVW-XYZL","deviceHash":"` + hash64 +
				`","nonce":"` + frozenNonce + `","pkg":"com.carlos.multiapp","ticketId":"t_1_deadbeef","ts":1757844000000}`,
			wantSHA:  "696469710df51e763b9e968d5502ecd285cc0e89e1cde6346ef7f53b8c1b3f57",
			wantSign: "9665e4144a3ad9b3baf6305d3d8d4cab2af324f2f55bbe1466854838f333fd67",
		},
	}

	for _, v := range vectors {
		// 1) 解析「带 sign 的完整请求体」→ 剔除 sign 并规范化，应逐字节等于冻结 canonical
		got, ts, nonce, _, pcode := parseSignedBody([]byte(withSign(v.canonical)))
		if pcode != CodeOK {
			t.Fatalf("[%s] parseSignedBody 失败 code=%d", v.name, pcode)
		}
		if ts != frozenTS || nonce != frozenNonce {
			t.Fatalf("[%s] ts/nonce 解析异常 ts=%d nonce=%s", v.name, ts, nonce)
		}
		if string(got) != v.canonical {
			t.Fatalf("[%s] canonical 不一致\n got=%s\nwant=%s", v.name, got, v.canonical)
		}
		// 2) SHA256_HEX(canonical)
		if sha := sha256Hex(got); sha != v.wantSHA {
			t.Fatalf("[%s] SHA256 不一致\n got=%s\nwant=%s", v.name, sha, v.wantSHA)
		}
		// 3) sign（第 3 槽为 OP）
		sig := computeSign(frozenSecret, "POST", v.op, frozenTS, frozenNonce, got)
		if sig != v.wantSign {
			t.Fatalf("[%s] sign 不一致（两端签名规范未对齐）\n got=%s\nwant=%s", v.name, sig, v.wantSign)
		}
	}
}

// TestEscapeBoundary 回归「转义边界」向量：< > & 不转义、中文原样、控制字符转义、
// U+2028 转义。若误用 Go 默认 json.Marshal（HTML 转义开启），本测试会立刻失败。
//
// 断言的 SHA256 与 UTF-8 字节长度由架构师独立复算（license-signature-testvectors.md §4），
// 与本测试相互印证——两侧算法若分叉，字节长度/SHA 必先暴露。
func TestEscapeBoundary(t *testing.T) {
	// 输入（JSON 转义序列，解析后为真实字符）：a"b\c + LF + d<e&f>中 + U+0001 + U+2028
	input := `{"deviceLabel":"a\"b\\c\nd<e&f>中\u0001\u2028","nonce":"` + frozenNonce +
		`","pkg":"com.carlos.multiapp","sign":"placeholder","ts":1757844000000}`

	want := `{"deviceLabel":"a\"b\\c\nd<e&f>中\u0001\u2028","nonce":"0123456789abcdef0123456789abcdef",` +
		`"pkg":"com.carlos.multiapp","ts":1757844000000}`

	// 架构师独立复算的锚点（license-signature-testvectors.md §4）。
	const wantSHA = "3ab81a5f66a9c8a00f69638b165af1d078e761342fdfe87055493bdce145ac2b"
	const wantLen = 138 // UTF-8 字节长度

	got, _, _, _, pcode := parseSignedBody([]byte(input))
	if pcode != CodeOK {
		t.Fatalf("parseSignedBody 失败 code=%d", pcode)
	}
	if string(got) != want {
		t.Fatalf("转义边界 canonical 不一致\n got=%s\nwant=%s", got, want)
	}
	if len(got) != wantLen {
		t.Fatalf("转义边界 UTF-8 字节长度不符: got=%d want=%d", len(got), wantLen)
	}
	if sha := sha256Hex(got); sha != wantSHA {
		t.Fatalf("转义边界 SHA256 不一致\n got=%s\nwant=%s", sha, wantSHA)
	}

	// 追加边界：\b \f 用短转义，U+0001 用 \u00xx（架构师 §4 追加边界）。
	got2, err := canonicalizeRaw([]byte(`{"d":"\b\f\u0001"}`))
	if err != nil {
		t.Fatalf("canonicalizeRaw 出错: %v", err)
	}
	if string(got2) != `{"d":"\b\f\u0001"}` {
		t.Fatalf("\\b\\f\\u0001 边界不一致\n got=%s\nwant=%s", got2, `{"d":"\b\f\u0001"}`)
	}
}
