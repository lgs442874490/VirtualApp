package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"io"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// maxBodyBytes 请求体上限（64KB），防御超大 body。
const maxBodyBytes = 64 * 1024

// challenge 一次握手下发的盐及其有效期。
type challenge struct {
	salt      string
	expiresAt int64
}

// Server HTTP 服务实例，持有数据库与运行时配置。
type Server struct {
	db       *sql.DB
	cfg      *Config
	nonceTTL int64

	mu         sync.Mutex
	challenges map[string]challenge

	// admin 管理后台会话/失败锁定运行态（2026-11-06）。
	admin *adminState
}

// NewServer 构造 Server。
func NewServer(db *sql.DB, cfg *Config) *Server {
	return &Server{
		db:         db,
		cfg:        cfg,
		nonceTTL:   cfg.NonceTTLMs,
		challenges: make(map[string]challenge),
		admin:      newAdminState(),
	}
}

// Mux 注册路由（Go 1.22 方法+路径模式）。
func (s *Server) Mux() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /v1/activate", s.handleActivate)
	mux.HandleFunc("POST /v1/verify", s.handleVerify)
	mux.HandleFunc("POST /v1/rebind", s.handleRebind)
	mux.HandleFunc("GET /healthz", s.handleHealth)
	// 管理后台（2026-11-06）：/admin 页面 + /admin/api/*；未配置口令时后台 503。
	s.registerAdminRoutes(mux)
	return mux
}

// ---------------------------------------------------------------------------
// 通用响应 / 日志辅助
// ---------------------------------------------------------------------------

// writeEnvelope 输出统一响应信封（业务错误也以 HTTP 200 + 信封 code 表达，便于客户端统一解析）。
func (s *Server) writeEnvelope(w http.ResponseWriter, code int, message string, data interface{}) {
	if data == nil {
		data = map[string]interface{}{}
	}
	env := Envelope{Code: code, Message: message, Data: data, ServerTime: nowMs()}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	if err := json.NewEncoder(w).Encode(env); err != nil {
		logWarn("写响应失败: %v", err)
	}
}

// writeOK 成功响应。
func (s *Server) writeOK(w http.ResponseWriter, data interface{}) {
	s.writeEnvelope(w, CodeOK, codeMessage(CodeOK), data)
}

// writeErr 错误响应，可选用自定义文案覆盖默认文案。
func (s *Server) writeErr(w http.ResponseWriter, code int, customMsg ...string) {
	msg := codeMessage(code)
	if len(customMsg) > 0 && customMsg[0] != "" {
		msg = customMsg[0]
	}
	s.writeEnvelope(w, code, msg, nil)
}

// readBody 读取并限制请求体大小，失败时直接回包并返回 ok=false。
func (s *Server) readBody(w http.ResponseWriter, r *http.Request) ([]byte, bool) {
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxBodyBytes))
	if err != nil {
		s.writeErr(w, CodeBadRequest, "请求体读取失败")
		return nil, false
	}
	return raw, true
}

// clientIP 获取客户端 IP（优先取 X-Forwarded-For 首段，兼容 Caddy 反代）。
func clientIP(r *http.Request) string {
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		if i := strings.IndexByte(xff, ','); i >= 0 {
			return strings.TrimSpace(xff[:i])
		}
		return strings.TrimSpace(xff)
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// logAction 输出结构化审计日志：绝不落明文 code，仅 code_id 与截断 device_hash（§11.8）。
func (s *Server) logAction(action string, codeID *int64, deviceHash string, resultCode int) {
	cid := "-"
	if codeID != nil {
		cid = strconvI64(*codeID)
	}
	log.Printf("[action=%s] code_id=%s device_hash=%s result_code=%d", action, cid, truncHash(deviceHash), resultCode)
}

// truncHash 截断哈希用于日志展示。
func truncHash(h string) string {
	if len(h) > 8 {
		return h[:8]
	}
	return h
}

// ---------------------------------------------------------------------------
// 处理器：activate（phase=hello / bind 两阶段）
// ---------------------------------------------------------------------------

func (s *Server) handleActivate(w http.ResponseWriter, r *http.Request) {
	raw, ok := s.readBody(w, r)
	if !ok {
		return
	}
	body, code := s.verifyRequest(r, opActivate, raw)
	if code != CodeOK {
		s.writeErr(w, code)
		return
	}

	var probe struct {
		Phase string `json:"phase"`
	}
	if err := json.Unmarshal(body, &probe); err != nil {
		s.writeErr(w, CodeBadRequest)
		return
	}
	switch probe.Phase {
	case "hello":
		s.handleHello(w, r, body)
	case "", "bind":
		s.handleBind(w, r, body)
	default:
		s.writeErr(w, CodeBadRequest, "未知的 phase 取值")
	}
}

// handleHello 下发指纹盐与 challengeId。
//
// 注意：指纹盐按服务端密钥**确定性派生**（见 fingerprintSalt），而非每批随机——
// 这保证「同码 + 同机」在重装/清数据后仍得到稳定指纹，一码一机判定不被误伤。
// challengeId 仍为一次性随机值（一次性消费 + TTL 由 lookupChallenge 保证）。
func (s *Server) handleHello(w http.ResponseWriter, r *http.Request, body []byte) {
	var req ActivateHelloReq
	if err := json.Unmarshal(body, &req); err != nil {
		s.writeErr(w, CodeBadRequest)
		return
	}
	// 指纹盐：按服务端密钥确定性派生，**不得使用随机值**。
	// 客户端 deviceHash = SHA256(ANDROID_ID|pkg|salt)，若盐每批随机，同一设备每次激活
	// 得到不同指纹，服务端一码一机比对必然判为「别的设备」→ 1003，违反契约 §5.2
	// 「同机幂等」，并在用户重装/清数据后造成永久锁死（只能运营 rebind 解救）。
	// 确定性盐使「同码 + 同机」指纹稳定 → 幂等成立、重装可自恢复。
	// 防重放不受影响：仍由 challengeId（一次性 + TTL）与 used_nonce 承担。
	salt := fingerprintSalt(s.cfg.Secret)
	cid := newUUID()
	s.mu.Lock()
	s.challenges[cid] = challenge{salt: salt, expiresAt: nowMs() + ChallengeTTLMs}
	s.purgeExpiredChallengesLocked()
	s.mu.Unlock()

	s.logAction(ActionActivate, nil, "", CodeOK)
	s.writeOK(w, HelloData{Salt: salt, ChallengeID: cid, ExpiresIn: ChallengeTTLMs / 1000})
}

// lookupChallenge 取出并消费（一次性）指定 challengeId 对应的盐。
func (s *Server) lookupChallenge(cid string) (challenge, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	ch, ok := s.challenges[cid]
	if !ok {
		return challenge{}, false
	}
	delete(s.challenges, cid)
	if nowMs() > ch.expiresAt {
		return challenge{}, false
	}
	return ch, true
}

// purgeExpiredChallengesLocked 清理过期挑战（调用方需持锁）。
func (s *Server) purgeExpiredChallengesLocked() {
	now := nowMs()
	for k, v := range s.challenges {
		if now > v.expiresAt {
			delete(s.challenges, k)
		}
	}
}

// handleBind 绑定设备并出票（一码一机）。
func (s *Server) handleBind(w http.ResponseWriter, r *http.Request, body []byte) {
	var req ActivateBindReq
	if err := json.Unmarshal(body, &req); err != nil {
		s.writeErr(w, CodeBadRequest)
		return
	}
	if req.DeviceHash == "" {
		s.writeErr(w, CodeBadRequest, "deviceHash 缺失")
		return
	}

	// 校验握手：challengeId 必须存在且未过期，且客户端回传的 salt 与下发一致。
	if req.ChallengeID == "" {
		s.writeErr(w, CodeChallengeInvalid)
		return
	}
	ch, ok := s.lookupChallenge(req.ChallengeID)
	if !ok || (req.Salt != "" && req.Salt != ch.salt) {
		s.writeErr(w, CodeChallengeInvalid)
		return
	}

	// 归一化并校验码格式
	codeStr := canonicalCode(req.Code)
	if !validateCode(codeStr) {
		s.logAction(ActionActivate, nil, req.DeviceHash, CodeFormatInvalid)
		s.writeErr(w, CodeFormatInvalid)
		return
	}
	c, err := getCodeByCode(s.db, codeStr)
	if err != nil {
		s.logAction(ActionActivate, nil, req.DeviceHash, CodeServerError)
		s.writeErr(w, CodeServerError)
		return
	}
	if c == nil {
		s.logAction(ActionActivate, nil, req.DeviceHash, CodeNotFound)
		s.writeErr(w, CodeNotFound)
		return
	}
	if c.Status == StatusRevoked {
		s.logAction(ActionActivate, codeIDPtr(c.ID), req.DeviceHash, CodeRevoked)
		s.writeErr(w, CodeRevoked)
		return
	}

	now := nowMs()

	// 已激活过的码：显式拒绝"已过期"，与 verify 路径口径一致（避免过期码激活时
	// 走幂等分支返回一张过期票、错误提示不准确）。
	if c.ActivatedAt.Valid && c.ExpireAt.Valid && c.ExpireAt.Int64 <= now {
		s.logAction(ActionActivate, codeIDPtr(c.ID), req.DeviceHash, CodeExpired)
		s.writeErr(w, CodeExpired)
		return
	}

	// 一码一机判定 + 首绑写入（原子，防并发击穿，见 bindDeviceAtomic）。
	expireAt := int64(0)
	expireValid := false
	durationDays := c.DurationDays
	if durationDays <= 0 {
		durationDays = MonthDurationDays
	}
	if c.Type == TypeMonth {
		expireAt = now + durationDays*MsPerDay
		expireValid = true
	}
	state, err := bindDeviceAtomic(s.db, c.ID, req.DeviceHash, req.DeviceLabel, now, expireAt, expireValid)
	if err != nil {
		s.logAction(ActionActivate, codeIDPtr(c.ID), req.DeviceHash, CodeServerError)
		s.writeErr(w, CodeServerError)
		return
	}
	switch state {
	case bindOther:
		s.logAction(ActionActivate, codeIDPtr(c.ID), req.DeviceHash, CodeAlreadyBound)
		s.writeErr(w, CodeAlreadyBound)
		return
	case bindFirst:
		// 首次绑定完成，重读以拿到权威 activated_at / expire_at
		c, err = getCodeByID(s.db, c.ID)
		if err != nil || c == nil {
			s.logAction(ActionActivate, codeIDPtr(c.ID), req.DeviceHash, CodeServerError)
			s.writeErr(w, CodeServerError)
			return
		}
	case bindSame:
		// 同机重复绑定：幂等成功，沿用本次读到的码数据出票
	}

	// 组装票据（2026-11-06：新增 role/features，旧客户端忽略未知字段）。
	activatedAt := int64(0)
	if c.ActivatedAt.Valid {
		activatedAt = c.ActivatedAt.Int64
	}
	ticketExpireAt := int64(0)
	if c.ExpireAt.Valid {
		ticketExpireAt = c.ExpireAt.Int64
	}
	role := roleOfCode(c)
	features, ferr := featuresForRole(s.db, role)
	if ferr != nil {
		s.logAction(ActionActivate, codeIDPtr(c.ID), req.DeviceHash, CodeServerError)
		s.writeErr(w, CodeServerError)
		return
	}
	ticket := Ticket{
		TicketID:     ticketIDFor(c.ID, req.DeviceHash),
		Code:         c.Code,
		Type:         c.Type,
		Tier:         codeTier(c),
		Role:         role,
		Features:     features,
		ActivatedAt:  activatedAt,
		ExpireAt:     ticketExpireAt,
		ServerStatus: c.Status,
	}
	insertLog(s.db, codeIDPtr(c.ID), req.DeviceHash, ActionActivate, CodeOK, clientIP(r), r.UserAgent(), now)
	s.logAction(ActionActivate, codeIDPtr(c.ID), req.DeviceHash, CodeOK)
	s.writeOK(w, map[string]interface{}{"ticket": ticket})
}

// ticketIDFor 由 (code_id, device_hash) 派生确定性票据 ID，保证同机重复激活返回同一票据。
func ticketIDFor(codeID int64, deviceHash string) string {
	sum := sha256Hex([]byte(strconvI64(codeID) + "|" + deviceHash))
	return "t_" + sum[:16]
}

// ---------------------------------------------------------------------------
// 处理器：verify
// ---------------------------------------------------------------------------

func (s *Server) handleVerify(w http.ResponseWriter, r *http.Request) {
	raw, ok := s.readBody(w, r)
	if !ok {
		return
	}
	body, code := s.verifyRequest(r, opVerify, raw)
	if code != CodeOK {
		s.writeErr(w, code)
		return
	}
	var req VerifyReq
	if err := json.Unmarshal(body, &req); err != nil {
		s.writeErr(w, CodeBadRequest)
		return
	}
	if req.DeviceHash == "" {
		s.writeErr(w, CodeBadRequest, "deviceHash 缺失")
		return
	}

	codeStr := canonicalCode(req.Code)
	if !validateCode(codeStr) {
		s.writeErr(w, CodeFormatInvalid)
		return
	}
	c, err := getCodeByCode(s.db, codeStr)
	if err != nil {
		s.writeErr(w, CodeServerError)
		return
	}
	if c == nil {
		s.writeErr(w, CodeNotFound)
		return
	}

	now := nowMs()
	active, err := getActiveBinding(s.db, c.ID)
	if err != nil {
		s.writeErr(w, CodeServerError)
		return
	}

	// 状态优先级（§11.6）：REVOKED > DEVICE_MISMATCH > EXPIRED > ACTIVE。
	//
	// 冻结契约 §5.1（消解 §5.1↔§5.3 张力）：
	//   只要码**存在且格式合法**，verify 一律返回 `code:0`，业务结果放
	//   `data.status ∈ {active, expired, revoked, device_mismatch}`；
	//   `1005`（expired）/`1006`（device_mismatch）/`1004`（revoked）**绝不作为返回码**，
	//   仅作为 `data.status` 的语义常量。唯一保留的非 0 业务码是 `1007`（未激活）。
	expireAt := int64(0)
	if c.ExpireAt.Valid {
		expireAt = c.ExpireAt.Int64
	}
	label := ""
	if active != nil {
		label = active.DeviceLabel
	}

	// status 初值留空：未激活分支据此保持零值（经 omitempty 省略），
	// 其余分支在 switch 内显式赋四值集合之一。
	status := ""
	resCode := CodeOK
	remainSeconds := int64(-1)

	switch {
	case c.Status == StatusRevoked:
		status = VerifyStatusRevoked
		remainSeconds = 0
	case !c.ActivatedAt.Valid:
		// 未激活就 verify：保留非 0 业务码 1007；
		// 冻结契约 §5.1 规定 data.status 为封闭四值集合，此分支**不返回 status**
		// （status 保持零值，经 `json:"status,omitempty"` 省略；客户端凭 code=1007 判定）。
		resCode = CodeNotActivated
		remainSeconds = 0
	case active == nil || active.DeviceHash != req.DeviceHash:
		status = VerifyStatusDeviceMismatch
		remainSeconds = 0
	case c.Type == TypeMonth && c.ExpireAt.Valid && c.ExpireAt.Int64 <= now:
		status = VerifyStatusExpired
		remainSeconds = 0
	default:
		status = VerifyStatusActive
		if c.Type == TypeMonth {
			remainSeconds = (expireAt - now) / 1000
		}
	}

	// 仅"匹配成功（active）"时更新绑定统计。
	if status == VerifyStatusActive && active != nil {
		if err := touchBinding(s.db, active.ID, now); err != nil {
			logWarn("更新 device_binding 失败: %v", err)
		}
	}

	insertLog(s.db, codeIDPtr(c.ID), req.DeviceHash, ActionVerify, resCode, clientIP(r), r.UserAgent(), now)
	s.logAction(ActionVerify, codeIDPtr(c.ID), req.DeviceHash, resCode)

	// 2026-11-06：随 verify 下发 role/features，App 用于刷新本地权限矩阵。
	// 未激活（1007）分支也返回该码的 role/features，与既有 tier 口径一致；App 不会据此放行。
	role := roleOfCode(c)
	features, ferr := featuresForRole(s.db, role)
	if ferr != nil {
		s.writeErr(w, CodeServerError)
		return
	}
	s.writeEnvelope(w, resCode, codeMessage(resCode), VerifyData{
		Status:        status,
		Type:          c.Type,
		Tier:          codeTier(c), // 批次7：客户端按此刷新本地票据等级（1/2/3）
		Role:          role,        // 2026-11-06：1/2/3/4（4=GM）
		Features:      features,    // 2026-11-06：当前角色可见功能 key（字典序）
		ExpireAt:      expireAt,
		RemainSeconds: remainSeconds,
		DeviceLabel:   label,
	})
}

// ---------------------------------------------------------------------------
// 处理器：rebind（运营方专用，需 adminToken）
// ---------------------------------------------------------------------------

func (s *Server) handleRebind(w http.ResponseWriter, r *http.Request) {
	raw, ok := s.readBody(w, r)
	if !ok {
		return
	}
	body, code := s.verifyRequest(r, opRebind, raw)
	if code != CodeOK {
		s.writeErr(w, code)
		return
	}
	var req RebindReq
	if err := json.Unmarshal(body, &req); err != nil {
		s.writeErr(w, CodeBadRequest)
		return
	}

	// 管理令牌常量时间比对
	if s.cfg.AdminToken == "" || !constantTimeEqual(req.AdminToken, s.cfg.AdminToken) {
		s.logAction(ActionRebind, nil, req.NewDeviceHash, CodeBadSignature)
		s.writeErr(w, CodeBadSignature, "管理员令牌校验失败")
		return
	}
	if req.NewDeviceHash == "" {
		s.writeErr(w, CodeBadRequest, "newDeviceHash 缺失")
		return
	}

	now := nowMs()
	resCode, expireAt, rebindCount, err := performRebind(s.db, s.cfg.RebindMax, req.Code, req.NewDeviceHash,
		req.Reason, now, clientIP(r), r.UserAgent())
	if err != nil {
		logWarn("rebind 执行失败: %v", err)
		s.writeErr(w, CodeServerError)
		return
	}
	if resCode != CodeOK {
		s.writeErr(w, resCode)
		return
	}
	s.logAction(ActionRebind, nil, req.NewDeviceHash, CodeOK)
	s.writeOK(w, RebindData{CodeExpr: "ok", ExpireAt: expireAt, RebindCount: rebindCount})
}

// handleHealth 健康检查。
func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	s.writeOK(w, map[string]interface{}{"status": "ok"})
}

// ---------------------------------------------------------------------------
// nonce 清理循环
// ---------------------------------------------------------------------------

// cleanupLoop 周期性清理过期 nonce。
func (s *Server) cleanupLoop(ctx context.Context) {
	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()
	s.cleanupNoncesOnce()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			s.cleanupNoncesOnce()
		}
	}
}

// cleanupNoncesOnce 执行一次 nonce 清理。
func (s *Server) cleanupNoncesOnce() {
	before := nowMs() - s.nonceTTL
	n, err := cleanupNonces(s.db, before)
	if err != nil {
		logWarn("清理 used_nonce 失败: %v", err)
		return
	}
	if n > 0 {
		log.Printf("[info] 已清理过期 nonce %d 条", n)
	}
}
