package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"sort"
	"strconv"
)

// signVersion 签名方案版本前缀（与 URL 版本解耦，客户端约定一致）。
const signVersion = "v1"

// 逻辑操作名 OP：签名串第 3 槽使用 OP（**不含 /v1 前缀**）。
//
// 架构师冻结修订（2026-09-14，见 license-signature-testvectors.md 顶部）：
// 用 OP 而非真实路径，避免与版本升级 / Caddy 反代 strip_prefix 耦合。
const (
	opActivate = "activate"
	opVerify   = "verify"
	opRebind   = "rebind"
)

// ---------------------------------------------------------------------------
// 规范化 JSON（canonical JSON）
//
// 规则（客户端必须字节级复现，详见 README「签名规范」）：
//  1. 对象键按 UTF-8 字节序升序排列；
//  2. 不输出任何多余空白（分隔符固定为 ',' 与 ':'）；
//  3. 数组保持原顺序，元素递归规范化；
//  4. 数字按 JSON 原始文本原样保留（不重新格式化，整数不会变成浮点）；
//  5. 字符串转义：'\"' '\\' '\n' '\r' '\t' '\b' '\f' 使用短转义，
//     其余 U+0000..U+001F 控制字符转 \u00xx，U+2028/U+2029 转 \u2028/\u2029；
//     '/'、'<'、'>'、'&' 与非 ASCII 字符（含中文）一律原样输出（UTF-8，不做 \uXXXX 转义）；
//  6. null 输出为字面量 null。
// ---------------------------------------------------------------------------

// canonicalizeRaw 将任意 JSON 字节串解析并输出规范化 JSON。
func canonicalizeRaw(raw []byte) ([]byte, error) {
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber() // 保留数字原始文本
	var v interface{}
	if err := dec.Decode(&v); err != nil {
		return nil, fmt.Errorf("JSON 解析失败: %w", err)
	}
	var buf bytes.Buffer
	if err := writeCanonical(&buf, v); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// writeCanonical 将已解析的 JSON 值写入规范化字节。
func writeCanonical(buf *bytes.Buffer, v interface{}) error {
	switch t := v.(type) {
	case nil:
		buf.WriteString("null")
	case bool:
		if t {
			buf.WriteString("true")
		} else {
			buf.WriteString("false")
		}
	case string:
		writeCanonicalString(buf, t)
	case json.Number:
		buf.WriteString(t.String())
	case float64:
		// 兜底：未走 UseNumber 时可能出现 float64。
		buf.WriteString(strconv.FormatFloat(t, 'g', -1, 64))
	case []interface{}:
		buf.WriteByte('[')
		for i, e := range t {
			if i > 0 {
				buf.WriteByte(',')
			}
			if err := writeCanonical(buf, e); err != nil {
				return err
			}
		}
		buf.WriteByte(']')
	case map[string]interface{}:
		keys := make([]string, 0, len(t))
		for k := range t {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		buf.WriteByte('{')
		for i, k := range keys {
			if i > 0 {
				buf.WriteByte(',')
			}
			writeCanonicalString(buf, k)
			buf.WriteByte(':')
			if err := writeCanonical(buf, t[k]); err != nil {
				return err
			}
		}
		buf.WriteByte('}')
	default:
		return fmt.Errorf("不支持的 JSON 类型: %T", v)
	}
	return nil
}

// writeCanonicalString 按规范化规则写出一个 JSON 字符串字面量。
//
// 与 Go 标准库 json（SetEscapeHTML(false)）保持一致：
//   - 短转义：\" \\ \n \r \t \b \f
//   - 其余 U+0000..U+001F 控制字符 → \u00xx
//   - U+2028 / U+2029 → \u2028 / \u2029（JS 行分隔符，恒转义）
//   - '<' '>' '&' 与 '/' **不转义**；非 ASCII（含中文）原样输出 UTF-8
func writeCanonicalString(buf *bytes.Buffer, s string) {
	const hexDigits = "0123456789abcdef"
	buf.WriteByte('"')
	for _, r := range s {
		switch r {
		case '"':
			buf.WriteString(`\"`)
		case '\\':
			buf.WriteString(`\\`)
		case '\n':
			buf.WriteString(`\n`)
		case '\r':
			buf.WriteString(`\r`)
		case '\t':
			buf.WriteString(`\t`)
		case '\b':
			buf.WriteString(`\b`)
		case '\f':
			buf.WriteString(`\f`)
		case '\u2028':
			buf.WriteString(`\u2028`)
		case '\u2029':
			buf.WriteString(`\u2029`)
		default:
			if r < 0x20 {
				buf.WriteString(`\u00`)
				buf.WriteByte(hexDigits[byte(r)>>4])
				buf.WriteByte(hexDigits[byte(r)&0x0f])
			} else {
				buf.WriteRune(r) // ASCII 与 UTF-8 多字节字符均原样输出
			}
		}
	}
	buf.WriteByte('"')
}

// canonicalizeMap 将请求体映射规范化为字节（键排序、去空白、数字原样）。
func canonicalizeMap(m map[string]interface{}) ([]byte, error) {
	var buf bytes.Buffer
	if err := writeCanonical(&buf, m); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// ---------------------------------------------------------------------------
// 摘要 / 签名原语
// ---------------------------------------------------------------------------

// sha256Hex 计算 SHA-256 并返回十六进制小写。
func sha256Hex(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

// hmacSHA256Hex 计算 HMAC-SHA256 并返回十六进制小写。
func hmacSHA256Hex(key []byte, data string) string {
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(data))
	return hex.EncodeToString(mac.Sum(nil))
}

// fingerprintSalt 由服务端密钥**确定性派生**指纹盐（返回 32 位小写 hex，即 16 字节）。
//
// 这是「同机幂等」的关键：客户端 deviceHash = SHA256(ANDROID_ID|pkg|salt)，
// 盐必须对同一部署稳定，否则用户重装 / 清数据 / 票据丢失后指纹漂移，服务端一码一机
// 比对会误判为「别的设备」→ 1003，造成永久锁死（只能运营 rebind 解救）。
//
// ⚠️ 修改派生口径（前缀 / 长度 / 算法）会使**所有既有绑定失效**，等价于全量换绑；
// 如需轮换，须同步走运营 rebind。当前口径：HMAC_SHA256(SECRET, "vp-fp-salt:v1") 取前 32 hex。
func fingerprintSalt(secret string) string {
	return hmacSHA256Hex([]byte(secret), "vp-fp-salt:v1")[:32]
}

// computeSign 计算请求签名（架构师冻结版，第 3 槽为 OP，不含 /v1 前缀）：
//
//	sign = HMAC_SHA256(APP_SECRET,
//	    "v1\n" + METHOD + "\n" + OP + "\n" + ts + "\n" + nonce + "\n" + SHA256_HEX(body_without_sign))
//
// OP ∈ { "activate", "verify", "rebind" }。
func computeSign(secret, method, op string, ts int64, nonce string, bodyWithoutSign []byte) string {
	payload := signVersion + "\n" + method + "\n" + op + "\n" +
		strconv.FormatInt(ts, 10) + "\n" + nonce + "\n" + sha256Hex(bodyWithoutSign)
	return hmacSHA256Hex([]byte(secret), payload)
}

// constantTimeEqual 常量时间字符串比较。
func constantTimeEqual(a, b string) bool {
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

// toInt64 将 JSON 解析出的数值宽松地转换为 int64。
func toInt64(v interface{}) (int64, bool) {
	switch t := v.(type) {
	case json.Number:
		if n, err := t.Int64(); err == nil {
			return n, true
		}
		if f, err := t.Float64(); err == nil {
			return int64(f), true
		}
	case float64:
		return int64(t), true
	case string:
		if n, err := strconv.ParseInt(t, 10, 64); err == nil {
			return n, true
		}
	}
	return 0, false
}

// parseSignedBody 解析「带 sign 的完整请求体」：校验必需字段后，
// **剔除 sign 字段**并输出规范化 JSON（body_without_sign）。
//
// 该函数同时供 verifyRequest 与单元测试使用，确保「测试路径」与「线上路径」
// 走完全相同的规范化代码，避免二者悄悄分叉。
func parseSignedBody(rawBody []byte) (bodyWithoutSign []byte, ts int64, nonce, sign string, code int) {
	dec := json.NewDecoder(bytes.NewReader(rawBody))
	dec.UseNumber()
	var m map[string]interface{}
	if err := dec.Decode(&m); err != nil {
		return nil, 0, "", "", CodeBadRequest
	}
	t, ok := toInt64(m["ts"])
	if !ok {
		return nil, 0, "", "", CodeBadRequest
	}
	n, _ := m["nonce"].(string)
	sg, _ := m["sign"].(string)
	if n == "" || sg == "" {
		return nil, 0, "", "", CodeBadRequest
	}
	delete(m, "sign")
	body, err := canonicalizeMap(m)
	if err != nil {
		return nil, 0, "", "", CodeBadRequest
	}
	return body, t, n, sg, CodeOK
}

// verifyRequest 完成时间戳窗口、签名、nonce 防重放三重校验。
//
// op 为逻辑操作名（"activate"/"verify"/"rebind"，**不含 /v1 前缀**），由各处理器显式传入。
// 成功返回 (去掉 sign 后的规范化请求体, CodeOK)；失败返回 (nil, 错误码)。
// 校验顺序：参数完整性(2004) → 时间戳窗口(2002) → 签名(2001) → nonce 防重放(2003)。
// 先验签再占用 nonce，可避免伪造请求批量污染 nonce 表。
func (s *Server) verifyRequest(r *http.Request, op string, rawBody []byte) ([]byte, int) {
	bodyWithoutSign, ts, nonce, sign, pcode := parseSignedBody(rawBody)
	if pcode != CodeOK {
		return nil, pcode
	}

	// 1) 时间戳窗口 ±5min
	now := nowMs()
	diff := now - ts
	if diff < 0 {
		diff = -diff
	}
	if diff > SignWindowMs {
		return nil, CodeTimestampSkew
	}

	// 2) 签名校验（常量时间）——第 3 槽使用 OP
	expected := computeSign(s.cfg.Secret, r.Method, op, ts, nonce, bodyWithoutSign)
	if !constantTimeEqual(expected, sign) {
		return nil, CodeBadSignature
	}

	// 3) nonce 防重放（签名通过后才占用）
	fresh, err := consumeNonce(s.db, nonce, now)
	if err != nil {
		logWarn("写入 used_nonce 失败: %v", err)
		return nil, CodeServerError
	}
	if !fresh {
		return nil, CodeNonceReplay
	}

	return bodyWithoutSign, CodeOK
}
