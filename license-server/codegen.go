package main

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"
)

// 兑换码字符集：剔除易混字符 I / O / 0 / 1 / L，共 32 个字符。
const codeAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

// 随机体长度（15 位）+ 1 位校验位 = 16 位，展示时按 4 位分组为 XXXX-XXXX-XXXX-XXXX。
const (
	codeRandomLen = 15
	codeTotalLen  = 16
)

// codeIndex 返回字符在字符集中的下标，未找到返回 -1。
func codeIndex(c byte) int {
	return strings.IndexByte(codeAlphabet, c)
}

// checkChar 根据前 15 位计算第 16 位校验位。
//
// 采用"位置加权和 mod 32"：weighted = Σ index_i * (i+1)，取模字符集长度后映射回字符。
// 该方案可捕获绝大多数单字符错误与相邻换位错误，且实现简单、双方易于对齐。
func checkChar(first15 string) byte {
	sum := 0
	for i := 0; i < len(first15); i++ {
		idx := codeIndex(first15[i])
		if idx < 0 {
			idx = 0 // 理论不会发生；容错处理，避免越界
		}
		sum += idx * (i + 1)
	}
	if sum < 0 {
		sum = -sum
	}
	return codeAlphabet[sum%len(codeAlphabet)]
}

// genRawCode 生成 16 位（含校验位）随机码，不含分组横线。
func genRawCode() (string, error) {
	body := make([]byte, codeRandomLen)
	if _, err := rand.Read(body); err != nil {
		return "", fmt.Errorf("生成随机码失败: %w", err)
	}
	var sb strings.Builder
	sb.Grow(codeTotalLen)
	for i := 0; i < codeRandomLen; i++ {
		sb.WriteByte(codeAlphabet[int(body[i])%len(codeAlphabet)])
	}
	first15 := sb.String()
	return first15 + string(checkChar(first15)), nil
}

// genCode 生成带分组横线的展示码，形如 XXXX-XXXX-XXXX-XXXX。
func genCode() (string, error) {
	raw, err := genRawCode()
	if err != nil {
		return "", err
	}
	return formatCode(raw), nil
}

// formatCode 将 16 位裸码按每 4 位插入横线。
func formatCode(raw string) string {
	var sb strings.Builder
	for i := 0; i < len(raw); i++ {
		if i > 0 && i%4 == 0 {
			sb.WriteByte('-')
		}
		sb.WriteByte(raw[i])
	}
	return sb.String()
}

// canonicalCode 归一化兑换码：去除横线与空白、转大写，长度合法时重新分组。
// 用于查库匹配（库中存储的是带横线的大写形式）。
func canonicalCode(s string) string {
	up := strings.ToUpper(strings.TrimSpace(s))
	raw := strings.NewReplacer("-", "", " ", "", "\t", "").Replace(up)
	if len(raw) == codeTotalLen {
		return formatCode(raw)
	}
	return raw
}

// validateCode 校验兑换码：字符集合法 + 长度正确 + 校验位一致。
func validateCode(s string) bool {
	raw := strings.NewReplacer("-", "", " ", "", "\t", "").Replace(strings.ToUpper(strings.TrimSpace(s)))
	if len(raw) != codeTotalLen {
		return false
	}
	for i := 0; i < len(raw); i++ {
		if codeIndex(raw[i]) < 0 {
			return false
		}
	}
	return checkChar(raw[:codeRandomLen]) == raw[codeRandomLen]
}

// codeHash 计算兑换码的 SHA-256（十六进制小写），用于索引与日志脱敏（§11.8）。
func codeHash(code string) string {
	sum := sha256.Sum256([]byte(code))
	return hex.EncodeToString(sum[:])
}
