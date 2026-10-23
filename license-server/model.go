package main

import "database/sql"

// ---------------------------------------------------------------------------
// 业务错误码（与客户端 LicenseConfig 对齐，见设计文档 §5.1）
// ---------------------------------------------------------------------------

const (
	// CodeOK 成功
	CodeOK = 0
	// CodeNotFound 兑换码不存在
	CodeNotFound = 1001
	// CodeFormatInvalid 兑换码格式错误 / 校验位不符
	CodeFormatInvalid = 1002
	// CodeAlreadyBound 该码已绑定其他设备（一码一机）
	CodeAlreadyBound = 1003
	// CodeRevoked 该码已被停用
	CodeRevoked = 1004
	// CodeExpired 授权已到期（verify 用）
	CodeExpired = 1005
	// CodeDeviceMismatch 设备不匹配
	CodeDeviceMismatch = 1006
	// CodeNotActivated 未激活就 verify
	CodeNotActivated = 1007
	// CodeRebindLimit 换绑超限
	CodeRebindLimit = 1008
	// CodeChallengeInvalid 盐 / 挑战失效
	CodeChallengeInvalid = 1009

	// CodeBadSignature 签名校验失败
	CodeBadSignature = 2001
	// CodeTimestampSkew 时间戳超范围
	CodeTimestampSkew = 2002
	// CodeNonceReplay nonce 重放
	CodeNonceReplay = 2003
	// CodeBadRequest 参数缺失 / 非法
	CodeBadRequest = 2004

	// CodeServerError 服务端内部错误
	CodeServerError = 3001
)

// codeMessages 错误码到用户可读文案的映射（服务端返回，客户端可另有两套文案）
var codeMessages = map[int]string{
	CodeOK:               "ok",
	CodeNotFound:         "兑换码不存在",
	CodeFormatInvalid:    "兑换码格式错误",
	CodeAlreadyBound:     "该兑换码已绑定其他设备",
	CodeRevoked:          "该兑换码已被停用",
	CodeExpired:          "授权已到期",
	CodeDeviceMismatch:   "设备不匹配",
	CodeNotActivated:     "兑换码尚未激活",
	CodeRebindLimit:      "换绑次数已用尽",
	CodeChallengeInvalid: "盐/挑战已失效",
	CodeBadSignature:     "签名校验失败",
	CodeTimestampSkew:    "请求时间戳超出允许范围",
	CodeNonceReplay:      "请求重复（nonce 重放）",
	CodeBadRequest:       "请求参数缺失或非法",
	CodeServerError:      "服务端内部错误",
}

// codeMessage 返回错误码对应文案，未知码返回通用文案。
func codeMessage(code int) string {
	if m, ok := codeMessages[code]; ok {
		return m
	}
	return "未知错误"
}

// ---------------------------------------------------------------------------
// 枚举常量
// ---------------------------------------------------------------------------

// 兑换码类型
const (
	TypeMonth     = "month"
	TypePermanent = "permanent"
)

// 兑换码状态
const (
	StatusActive    = "active"
	StatusRevoked   = "revoked"
	StatusExhausted = "exhausted"
)

// 授权等级（tier，批次7 三级权限 2026-10-23）：
//
//	1 = 普通（只有分身）
//	2 = 会员（日常脚本）
//	3 = 高级会员（高阶功能）
//
// **存量兼容**：schema `tier INTEGER NOT NULL DEFAULT 3`，旧库迁移补列后存量码一律 3；
// 未指定等级的旧 CLI/客户端路径也按 TierPremium 处理，绝不降权老用户。
const (
	TierBasic   = 1
	TierMember  = 2
	TierPremium = 3
	// TierDefault 缺省等级 = 高级会员（存量码/未指定 tier 的新码）。
	TierDefault = TierPremium
)

// normalizeTier 归一化等级：任意 int → 1/2/3 合法集合；0/负/超界/未知值一律按
// TierPremium（存量兼容，宁可给高不误降权）。DB 侧另有 CHECK (tier IN (1,2,3)) 兜底。
func normalizeTier(t int) int {
	if t < TierBasic || t > TierPremium {
		return TierDefault
	}
	return t
}

// codeTier 取一条码的生效等级（nil 防御；旧数据/零值按 TierDefault）。
func codeTier(c *LicenseCode) int {
	if c == nil {
		return TierDefault
	}
	return normalizeTier(int(c.Tier))
}

// 设备绑定状态
const (
	BindingActive  = "active"
	BindingUnbound = "unbound"
)

// verify 接口返回的 status —— 冻结契约 §5.1 规定为**封闭四值集合**：
//
//	{active, expired, revoked, device_mismatch}
//
// 说明：只要码存在且格式合法，verify 一律返回 code:0，业务结果放此 status。
// 错误码 1007（未激活就 verify）**不占用 status**（以保持集合封闭）：
// 该分支返回非 0 code:1007，且**不返回 status 字段**（其字段带 omitempty，
// 由 server.go 为该分支留零值），客户端仅凭 code 判定为"未激活"。
const (
	VerifyStatusActive         = "active"
	VerifyStatusExpired        = "expired"
	VerifyStatusRevoked        = "revoked"
	VerifyStatusDeviceMismatch = "device_mismatch"
)

// 流水动作
const (
	ActionActivate = "activate"
	ActionVerify   = "verify"
	ActionRebind   = "rebind"
	ActionRevoke   = "revoke"
)

// 常量（与客户端语义一致，见 §11.9）
const (
	// MonthDurationDays 月付码时长（天）
	MonthDurationDays = 30
	// SignWindowMs 签名时间戳允许的偏移窗口（±5 分钟）
	SignWindowMs = 5 * 60 * 1000
	// ChallengeTTLMs 握手盐 / challengeId 有效期（5 分钟）
	ChallengeTTLMs = 5 * 60 * 1000
	// NonceTTLDefaultSec nonce 防重放记录默认存活秒数（15 分钟，须大于签名窗口）
	NonceTTLDefaultSec = 15 * 60
	// RebindMaxDefault 换绑次数上限默认值。
	// 用户拍板：一码一次激活、**禁止换绑**，默认 0（= 禁止）。
	// 取值语义：0 = 禁止换绑；>0 = 每码限换绑 N 次；-1 = 不限。
	// 将来客服需人工救设备时，设 env LICENSE_REBIND_MAX=1 临时开放。
	RebindMaxDefault = 0
	// MsPerDay 一天的毫秒数
	MsPerDay = 24 * 60 * 60 * 1000
)

// ---------------------------------------------------------------------------
// 数据库行结构体
// ---------------------------------------------------------------------------

// LicenseCode 对应 license_code 表一行。
type LicenseCode struct {
	ID           int64
	Code         string
	CodeHash     string
	Type         string
	Tier         int64 // 授权等级 1/2/3（批次7；存量码迁移后默认 3）
	DurationDays int64
	Status       string
	Remark       string
	CreatedAt    int64
	ActivatedAt  sql.NullInt64
	ExpireAt     sql.NullInt64
	RevokedAt    sql.NullInt64
	RevokeReason sql.NullString
	CreatedBy    string
}

// DeviceBinding 对应 device_binding 表一行。
type DeviceBinding struct {
	ID           int64
	CodeID       int64
	DeviceHash   string
	DeviceLabel  string
	Status       string
	BoundAt      int64
	LastSeenAt   sql.NullInt64
	LastVerifyAt sql.NullInt64
	VerifyCount  int64
}

// ---------------------------------------------------------------------------
// 请求结构体（字段命名 camelCase，与客户端一致，见 §11.3）
// ---------------------------------------------------------------------------

// ActivateHelloReq activate 阶段一（phase=hello）
type ActivateHelloReq struct {
	Phase          string `json:"phase"`
	Pkg            string `json:"pkg"`
	AppVersion     string `json:"appVersion"`
	AppVersionCode int64  `json:"appVersionCode"`
	Ts             int64  `json:"ts"`
	Nonce          string `json:"nonce"`
	Sign           string `json:"sign"`
}

// ActivateBindReq activate 阶段二（phase=bind）
type ActivateBindReq struct {
	Phase          string `json:"phase"`
	Code           string `json:"code"`
	DeviceHash     string `json:"deviceHash"`
	DeviceLabel    string `json:"deviceLabel"`
	Salt           string `json:"salt"`
	ChallengeID    string `json:"challengeId"`
	Pkg            string `json:"pkg"`
	AppVersion     string `json:"appVersion"`
	AppVersionCode int64  `json:"appVersionCode"`
	Ts             int64  `json:"ts"`
	Nonce          string `json:"nonce"`
	Sign           string `json:"sign"`
}

// VerifyReq verify 接口请求
type VerifyReq struct {
	Code       string `json:"code"`
	TicketID   string `json:"ticketId"`
	DeviceHash string `json:"deviceHash"`
	Pkg        string `json:"pkg"`
	AppVersion string `json:"appVersion"`
	Ts         int64  `json:"ts"`
	Nonce      string `json:"nonce"`
	Sign       string `json:"sign"`
}

// RebindReq rebind 接口请求
type RebindReq struct {
	Code          string `json:"code"`
	NewDeviceHash string `json:"newDeviceHash"`
	AdminToken    string `json:"adminToken"`
	Reason        string `json:"reason"`
	Ts            int64  `json:"ts"`
	Nonce         string `json:"nonce"`
	Sign          string `json:"sign"`
}

// ---------------------------------------------------------------------------
// 响应结构体
// ---------------------------------------------------------------------------

// Envelope 统一响应信封（§5.0）
type Envelope struct {
	Code       int         `json:"code"`
	Message    string      `json:"message"`
	Data       interface{} `json:"data"`
	ServerTime int64       `json:"serverTime"`
}

// Ticket activate 成功后下发的票据
type Ticket struct {
	TicketID     string `json:"ticketId"`
	Code         string `json:"code"`
	Type         string `json:"type"`
	Tier         int    `json:"tier"` // 授权等级 1/2/3（批次7；缺省/旧服务端按 3 兼容）
	ActivatedAt  int64  `json:"activatedAt"`
	ExpireAt     int64  `json:"expireAt"`
	ServerStatus string `json:"serverStatus"`
}

// HelloData activate phase=hello 响应
type HelloData struct {
	Salt        string `json:"salt"`
	ChallengeID string `json:"challengeId"`
	ExpiresIn   int64  `json:"expiresIn"`
}

// VerifyData verify 响应
//
// status 为封闭四值集合 {active,expired,revoked,device_mismatch}；
// 未激活分支（code=1007）不返回 status，故此处带 omitempty（零值即省略）。
type VerifyData struct {
	Status        string `json:"status,omitempty"`
	Type          string `json:"type"`
	Tier          int    `json:"tier"` // 授权等级 1/2/3（批次7；客户端按此刷新本地票据）
	ExpireAt      int64  `json:"expireAt"`
	RemainSeconds int64  `json:"remainSeconds"`
	DeviceLabel   string `json:"deviceLabel"`
}

// RebindData rebind 响应（字段名 code_expr 按设计文档 §5.4 照抄）
type RebindData struct {
	CodeExpr    string `json:"code_expr"`
	ExpireAt    int64  `json:"expireAt"`
	RebindCount int64  `json:"rebindCount"`
}
