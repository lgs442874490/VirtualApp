package main

import (
	"crypto/rand"
	"database/sql"
	"encoding/csv"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

// ---------------------------------------------------------------------------
// 管理后台（2026-11-06）
//
// 形态：license-server 内置 /admin 单页 + /admin/api/* JSON API。
// 鉴权：单管理员口令（LICENSE_ADMIN_PASSWORD）+ 内存会话 cookie（8 小时）。
// 安全：口令常量时间比较；同 IP 连续失败 5 次锁 10 分钟；写操作校验 Origin；
// 业务错误统一 HTTP 200 + 信封 code（鉴权错误 401/403），与授权接口口径一致。
// ---------------------------------------------------------------------------

const (
	// adminCookieName 会话 cookie 名。
	adminCookieName = "lic_admin_sid"
	// adminSessionTTL 会话有效期（8 小时）。
	adminSessionTTL = 8 * 3600 * 1000
	// adminMaxFails 同 IP 连续失败上限。
	adminMaxFails = 5
	// adminLockMs 锁定时长（10 分钟）。
	adminLockMs = 10 * 60 * 1000
	// adminExportMax 单次导出上限。
	adminExportMax = 10000

	// 管理 API 业务码（HTTP 200 + code）。
	adminCodeBadRequest = 4001
	adminCodeNotFound   = 4002
	adminCodeConflict   = 4003
	adminCodeForbidden  = 4004
	adminCodeServer     = 5001
	// adminCodeNoPassword 未配置管理员口令。
	adminCodeNoPassword = 5003
)

// adminFail 登录失败计数（带时间衰减：窗口外的历史失败不再累计，S8）。
type adminFail struct {
	count    int
	until    int64
	lastFail int64
}

// adminFailWindowMs 失败计数衰减窗口：距上次失败超过该窗口则重新计数。
const adminFailWindowMs = 10 * 60 * 1000

// adminState 管理后台运行态（挂在 Server 上，便于测试隔离）。
type adminState struct {
	mu       sync.Mutex
	sessions map[string]int64 // sid -> expiresAt(ms)
	fails    map[string]*adminFail
}

func newAdminState() *adminState {
	return &adminState{sessions: map[string]int64{}, fails: map[string]*adminFail{}}
}

// registerAdminRoutes 注册管理后台路由（Mux 内调用）。
func (s *Server) registerAdminRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /admin/api/login", s.handleAdminLogin)
	mux.HandleFunc("POST /admin/api/logout", s.handleAdminLogout)
	mux.HandleFunc("GET /admin/api/me", s.handleAdminMe)
	mux.HandleFunc("GET /admin/api/overview", s.handleAdminOverview)
	mux.HandleFunc("GET /admin/api/codes", s.handleAdminCodes)
	mux.HandleFunc("POST /admin/api/codes/generate", s.handleAdminGenerate)
	mux.HandleFunc("GET /admin/api/codes/export", s.handleAdminCodesExport)
	mux.HandleFunc("POST /admin/api/codes/batch", s.handleAdminBatch)
	mux.HandleFunc("GET /admin/api/codes/{id}", s.handleAdminCodeDetail)
	mux.HandleFunc("POST /admin/api/codes/{id}/revoke", s.handleAdminRevoke)
	mux.HandleFunc("POST /admin/api/codes/{id}/restore", s.handleAdminRestore)
	mux.HandleFunc("GET /admin/api/codes/{id}/logs", s.handleAdminLogs)
	mux.HandleFunc("GET /admin/api/codes/{id}/logs/export", s.handleAdminLogsExport)
	mux.HandleFunc("GET /admin/api/features", s.handleAdminFeatures)
	mux.HandleFunc("GET /admin/api/matrix", s.handleAdminMatrix)
	mux.HandleFunc("PUT /admin/api/matrix", s.handleAdminMatrixSave)
	mux.HandleFunc("POST /admin/api/matrix/reset", s.handleAdminMatrixReset)
	mux.HandleFunc("GET /admin/api/audit", s.handleAdminAudit)
	// 静态页：/admin 与 /admin/ 都交给 handleAdminWeb（内部区分 index 与资源）。
	mux.HandleFunc("GET /admin", s.handleAdminWeb)
	mux.HandleFunc("GET /admin/", s.handleAdminWeb)
}

// ---------------------------------------------------------------------------
// 会话与鉴权
// ---------------------------------------------------------------------------

// adminMinPasswordLen 管理后台口令最小长度（需求 REQ-ADMIN-02）。
const adminMinPasswordLen = 16

// adminConfigured 是否已配置**合规**口令；未配置或长度不足时后台整体 503
// （授权接口 /v1/* 不受影响，避免短口令导致整个服务不可用）。
func (s *Server) adminConfigured() bool {
	return s.cfg != nil && utf8.RuneCountInString(s.cfg.AdminPassword) >= adminMinPasswordLen
}

// writeAdminJSON 输出管理 API 信封（HTTP 指定状态码）。
func (s *Server) writeAdminJSON(w http.ResponseWriter, httpStatus, code int, message string, data interface{}) {
	if data == nil {
		data = map[string]interface{}{}
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(httpStatus)
	_ = json.NewEncoder(w).Encode(Envelope{Code: code, Message: message, Data: data, ServerTime: nowMs()})
}

// writeAdminOK 成功响应。
func (s *Server) writeAdminOK(w http.ResponseWriter, data interface{}) {
	s.writeAdminJSON(w, http.StatusOK, 0, "ok", data)
}

// writeAdminErr 业务错误（HTTP 200 + 信封 code，与授权接口口径一致）。
func (s *Server) writeAdminErr(w http.ResponseWriter, code int, message string) {
	if message == "" {
		message = "操作失败"
	}
	s.writeAdminJSON(w, http.StatusOK, code, message, nil)
}

// newSessionID 生成 32 字节随机会话 ID。
func newSessionID() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

// issueSession 创建会话并下发 cookie。
func (s *Server) issueSession(w http.ResponseWriter, r *http.Request) error {
	sid, err := newSessionID()
	if err != nil {
		return err
	}
	now := nowMs()
	st := s.admin
	st.mu.Lock()
	// 顺带清理过期会话，避免内存无限增长。
	for k, exp := range st.sessions {
		if exp <= now {
			delete(st.sessions, k)
		}
	}
	st.sessions[sid] = now + adminSessionTTL
	st.mu.Unlock()

	secure := r.TLS != nil || strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https")
	http.SetCookie(w, &http.Cookie{
		Name:     adminCookieName,
		Value:    sid,
		Path:     "/admin",
		HttpOnly: true,
		SameSite: http.SameSiteStrictMode,
		Secure:   secure,
		MaxAge:   int(adminSessionTTL / 1000),
	})
	return nil
}

// currentSession 校验当前会话（不存在/过期返回 false）。
func (s *Server) currentSession(r *http.Request) (string, bool) {
	c, err := r.Cookie(adminCookieName)
	if err != nil || c.Value == "" {
		return "", false
	}
	now := nowMs()
	st := s.admin
	st.mu.Lock()
	defer st.mu.Unlock()
	exp, ok := st.sessions[c.Value]
	if !ok || exp <= now {
		delete(st.sessions, c.Value)
		return "", false
	}
	return c.Value, true
}

// requireAdmin 鉴权 + 写操作 Origin 校验。返回 false 时已写响应。
func (s *Server) requireAdmin(w http.ResponseWriter, r *http.Request) bool {
	if !s.adminConfigured() {
		s.writeAdminJSON(w, http.StatusServiceUnavailable, adminCodeNoPassword,
			"未配置管理员口令（LICENSE_ADMIN_PASSWORD）", nil)
		return false
	}
	if _, ok := s.currentSession(r); !ok {
		s.writeAdminJSON(w, http.StatusUnauthorized, 401, "未登录或会话已过期", nil)
		return false
	}
	// 写操作来源校验（S5 温和版）：优先 Origin，缺失时回退 Referer；
	// 两者都缺（如 curl/运维脚本）放行——会话 cookie 仍必须有效，SameSite=Strict 已挡跨站。
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		if !s.sameOriginRequest(r) {
			s.writeAdminJSON(w, http.StatusForbidden, adminCodeForbidden, "来源校验失败", nil)
			return false
		}
	}
	return true
}

// adminClientIP 取客户端 IP（与授权接口一致）。
func adminClientIP(r *http.Request) string { return clientIP(r) }

// sameOriginRequest 判断写请求来源是否与本站同源（Origin 优先，Referer 回退）。
// 两个头都缺失时返回 true（放行无浏览器上下文的运维调用）。
func (s *Server) sameOriginRequest(r *http.Request) bool {
	if origin := r.Header.Get("Origin"); origin != "" {
		u, err := url.Parse(origin)
		return err == nil && strings.EqualFold(u.Host, r.Host)
	}
	if ref := r.Header.Get("Referer"); ref != "" {
		u, err := url.Parse(ref)
		return err == nil && strings.EqualFold(u.Host, r.Host)
	}
	return true
}

// ---------------------------------------------------------------------------
// 登录 / 登出 / 会话信息
// ---------------------------------------------------------------------------

// handleAdminLogin 口令登录。
func (s *Server) handleAdminLogin(w http.ResponseWriter, r *http.Request) {
	if !s.adminConfigured() {
		s.writeAdminJSON(w, http.StatusServiceUnavailable, adminCodeNoPassword,
			"未配置管理员口令（LICENSE_ADMIN_PASSWORD）", nil)
		return
	}
	ip := adminClientIP(r)
	v := &adminFail{}
	st := s.admin
	st.mu.Lock()
	if f, ok := st.fails[ip]; ok {
		v = f
		// 时间衰减：距上次失败超过窗口 → 重新计数（避免历史失败累积误锁）。
		if v.lastFail > 0 && nowMs()-v.lastFail > adminFailWindowMs {
			v.count = 0
			v.until = 0
		}
	}
	now := nowMs()
	if v.until > now {
		left := (v.until - now + 999) / 1000
		st.mu.Unlock()
		s.writeAdminJSON(w, http.StatusTooManyRequests, 429,
			fmt.Sprintf("失败次数过多，请 %d 秒后再试", left), nil)
		return
	}
	st.mu.Unlock()

	var req struct {
		Password string `json:"password"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64*1024)).Decode(&req); err != nil {
		s.writeAdminJSON(w, http.StatusBadRequest, adminCodeBadRequest, "请求格式错误", nil)
		return
	}
	if !constantTimeEqual(req.Password, s.cfg.AdminPassword) {
		st.mu.Lock()
		v.count++
		v.lastFail = now
		if v.count >= adminMaxFails {
			v.count = 0
			v.until = now + adminLockMs
		}
		st.fails[ip] = v
		// 顺手清理过期/陈旧失败项，防 map 无界增长。
		if len(st.fails) > 1024 {
			for k, f := range st.fails {
				if f.until > 0 && f.until <= now {
					delete(st.fails, k)
				}
			}
		}
		st.mu.Unlock()
		// 失败登录也写审计（S8）：安全事件可追溯；不记口令内容。
		_ = insertAdminAudit(s.db, "admin", AdminActionLogin, "", "",
			marshalJSON(map[string]interface{}{"ok": false}), ip)
		s.writeAdminJSON(w, http.StatusUnauthorized, 401, "口令错误", nil)
		return
	}

	// 成功：清失败计数、建会话、写审计。
	st.mu.Lock()
	delete(st.fails, ip)
	st.mu.Unlock()
	if err := s.issueSession(w, r); err != nil {
		s.writeAdminJSON(w, http.StatusInternalServerError, adminCodeServer, "会话创建失败", nil)
		return
	}
	_ = insertAdminAudit(s.db, "admin", AdminActionLogin, "", "",
		marshalJSON(map[string]interface{}{"ok": true}), ip)
	s.writeAdminOK(w, map[string]interface{}{"ok": true})
}

// handleAdminLogout 注销当前会话（幂等；未配置口令时同样 503，与后台整体口径一致）。
func (s *Server) handleAdminLogout(w http.ResponseWriter, r *http.Request) {
	if !s.adminConfigured() {
		s.writeAdminJSON(w, http.StatusServiceUnavailable, adminCodeNoPassword,
			"未配置管理员口令（LICENSE_ADMIN_PASSWORD）", nil)
		return
	}
	if sid, ok := s.currentSession(r); ok {
		s.admin.mu.Lock()
		delete(s.admin.sessions, sid)
		s.admin.mu.Unlock()
	}
	http.SetCookie(w, &http.Cookie{
		Name: adminCookieName, Value: "", Path: "/admin", HttpOnly: true,
		SameSite: http.SameSiteStrictMode, MaxAge: -1,
	})
	s.writeAdminOK(w, map[string]interface{}{"ok": true})
}

// handleAdminMe 当前会话信息。
func (s *Server) handleAdminMe(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	s.writeAdminOK(w, map[string]interface{}{"authenticated": true})
}

// ---------------------------------------------------------------------------
// 概览
// ---------------------------------------------------------------------------

func (s *Server) handleAdminOverview(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	o, err := overviewStats(s.db)
	if err != nil {
		s.writeAdminErr(w, adminCodeServer, "统计查询失败")
		return
	}
	s.writeAdminOK(w, o)
}

// ---------------------------------------------------------------------------
// 兑换码：列表 / 生成 / 详情 / 启停 / 批量 / 记录 / 导出
// ---------------------------------------------------------------------------

// totalPages 计算总页数（total=0 时返回 0）。
func totalPages(total int64, size int) int {
	if size <= 0 {
		return 0
	}
	return int((total + int64(size) - 1) / int64(size))
}

// parseCodeFilter 从 query 解析列表筛选（page/size/sort 也在此收敛）。
func parseCodeFilter(r *http.Request) CodeFilter {
	q := r.URL.Query()
	page, _ := strconv.Atoi(q.Get("page"))
	size, _ := strconv.Atoi(q.Get("size"))
	f := CodeFilter{
		Status:     strings.TrimSpace(q.Get("status")),
		Usage:      strings.TrimSpace(q.Get("usage")),
		Tier:       strings.TrimSpace(q.Get("tier")),
		Type:       strings.TrimSpace(q.Get("type")),
		Keyword:    strings.TrimSpace(q.Get("q")),
		From:       parseEpochQuery(q.Get("from")),
		To:         parseEpochQuery(q.Get("to")),
		ExpireFrom: parseEpochQuery(q.Get("expireFrom")),
		ExpireTo:   parseEpochQuery(q.Get("expireTo")),
		Page:       page,
		Size:       size,
		Sort:       strings.TrimSpace(q.Get("sort")),
	}
	f.Tier = strings.ToLower(f.Tier) // 兼容 GM/1/2/3 大小写
	f.Keyword = truncateText(f.Keyword, 64)
	return f
}

// handleAdminCodes 兑换码列表。
func (s *Server) handleAdminCodes(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	f := parseCodeFilter(r)
	items, total, err := listCodes(s.db, f)
	if err != nil {
		s.writeAdminErr(w, adminCodeServer, "查询兑换码失败")
		return
	}
	page, size := normalizePage(f.Page, f.Size)
	s.writeAdminOK(w, map[string]interface{}{
		"items": items, "total": total, "page": page, "size": size,
		"totalPages": totalPages(total, size),
	})
}

// generateCodes 批量生成（事务内写入；任一条失败整批回滚）。
//
// 唯一性（2026-11-06 审查修订，REQ-CODE-03）：在事务内逐条生成并检查/写入；
// 若 INSERT 命中 UNIQUE 冲突（极端碰撞），重新生成该条并最多重试 5 次；
// 5 次仍失败则整批回滚。事务内查询与写入共用同一连接（*sql.Tx），不会自我阻塞。
func (s *Server) generateCodes(count int, typ string, tier int, isGM bool, remark, actor, ip string) ([]CodeListItem, error) {
	durationDays := int64(0)
	if typ == TypeMonth {
		durationDays = MonthDurationDays
	}
	now := nowMs()
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()

	items := make([]CodeListItem, 0, count)
	ids := make([]int64, 0, count)
	gm := 0
	if isGM {
		gm = 1
	}
	for i := 0; i < count; i++ {
		inserted := false
		var lastErr error
		for attempt := 0; attempt < 5; attempt++ {
			code, gerr := genCode()
			if gerr != nil {
				return nil, gerr
			}
			var exists int
			qerr := tx.QueryRow(`SELECT 1 FROM license_code WHERE code = ? LIMIT 1`, code).Scan(&exists)
			if qerr == nil {
				continue // 已存在：重试
			}
			if !errors.Is(qerr, sql.ErrNoRows) {
				return nil, qerr
			}
			res, ierr := tx.Exec(`INSERT INTO license_code
				(code, code_hash, type, tier, is_gm, duration_days, status, remark, created_at, created_by)
				VALUES (?, ?, ?, ?, ?, ?, 'active', ?, ?, ?)`,
				code, codeHash(code), typ, normalizeTier(tier), gm, durationDays, remark, now, actor)
			if ierr != nil {
				lastErr = ierr
				if strings.Contains(ierr.Error(), "UNIQUE constraint failed") {
					continue // 并发/竞态碰撞：重试该条
				}
				return nil, fmt.Errorf("写入兑换码失败: %w", ierr)
			}
			id, ierr := res.LastInsertId()
			if ierr != nil {
				return nil, ierr
			}
			ids = append(ids, id)
			items = append(items, CodeListItem{
				ID:         id,
				Code:       code,
				Type:       typ,
				Tier:       int64(normalizeTier(tier)),
				Role:       roleOfGenerated(tier, isGM),
				IsGM:       isGM,
				Status:     StatusActive,
				Usage:      "unused",
				RemainDays: -2,
				Remark:     remark,
				CreatedAt:  now,
				CreatedBy:  actor,
			})
			inserted = true
			break
		}
		if !inserted {
			return nil, fmt.Errorf("生成唯一兑换码失败（重试 5 次）: %v", lastErr)
		}
	}
	// 审计与生成同事务（S6）：审计失败则整批回滚，杜绝"码已发、审计缺失"。
	if err := insertAdminAuditTx(tx, actor, AdminActionGenerate, "license_code", "",
		marshalJSON(map[string]interface{}{
			"count": count, "type": typ, "tier": tier, "isGm": isGM, "remark": remark, "ids": ids,
		}), ip); err != nil {
		return nil, fmt.Errorf("写入审计失败，本批未生成: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return items, nil
}

// roleOfGenerated 生成响应里的角色档位。
func roleOfGenerated(tier int, isGM bool) int {
	if isGM {
		return RoleGM
	}
	if tier < 1 || tier > 3 {
		return 3
	}
	return tier
}

// handleAdminGenerate 批量生成兑换码。
func (s *Server) handleAdminGenerate(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	var req struct {
		Count  int    `json:"count"`
		Type   string `json:"type"`
		Tier   string `json:"tier"`
		Remark string `json:"remark"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64*1024)).Decode(&req); err != nil {
		s.writeAdminErr(w, adminCodeBadRequest, "请求格式错误")
		return
	}
	if req.Count < 1 || req.Count > 100 {
		s.writeAdminErr(w, adminCodeBadRequest, "生成数量必须是 1–100")
		return
	}
	if req.Type != TypeMonth && req.Type != TypePermanent {
		s.writeAdminErr(w, adminCodeBadRequest, "类型只能是 month 或 permanent")
		return
	}
	if len([]rune(req.Remark)) > 100 {
		s.writeAdminErr(w, adminCodeBadRequest, "备注不能超过 100 字")
		return
	}
	tierStr := strings.ToLower(strings.TrimSpace(req.Tier))
	isGM := tierStr == "gm"
	tier := TierPremium
	if !isGM {
		var err error
		tier, err = parseTier(tierStr)
		if err != nil {
			s.writeAdminErr(w, adminCodeBadRequest, "等级只能是 1/2/3/gm")
			return
		}
	}
	items, err := s.generateCodes(req.Count, req.Type, tier, isGM, req.Remark, "admin", adminClientIP(r))
	if err != nil {
		logWarn("批量生成失败: %v", err)
		s.writeAdminErr(w, adminCodeServer, "生成失败："+err.Error())
		return
	}
	s.writeAdminOK(w, map[string]interface{}{"codes": items})
}

// handleAdminCodeDetail 单码详情。
func (s *Server) handleAdminCodeDetail(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		s.writeAdminErr(w, adminCodeBadRequest, "非法兑换码 ID")
		return
	}
	item, bindings, verifyCount, err := getCodeDetail(s.db, id)
	if err != nil {
		s.writeAdminErr(w, adminCodeServer, "查询详情失败")
		return
	}
	if item == nil {
		s.writeAdminErr(w, adminCodeNotFound, "兑换码不存在")
		return
	}
	s.writeAdminOK(w, map[string]interface{}{
		"code": item, "bindings": bindings, "verifyCount": verifyCount,
	})
}

// doRevoke 停用单码（内部复用）。返回是否实际变更。
func (s *Server) doRevoke(id int64, reason, actor, ip string) error {
	c, err := getCodeByID(s.db, id)
	if err != nil {
		return err
	}
	if c == nil {
		return errAdminNotFound
	}
	if c.Status == StatusRevoked {
		return nil // 幂等
	}
	if strings.TrimSpace(reason) == "" {
		reason = "后台停用"
	}
	if err := revokeCodeByID(s.db, id, reason, nowMs()); err != nil {
		return err
	}
	if err := insertAdminAudit(s.db, actor, AdminActionRevoke, "license_code", strconvI64(id),
		marshalJSON(map[string]interface{}{"reason": reason}), ip); err != nil {
		return errAdminAuditFailed
	}
	return nil
}

// doRestore 启用单码（内部复用）。已过期码拒绝。
func (s *Server) doRestore(id int64, actor, ip string) error {
	c, err := getCodeByID(s.db, id)
	if err != nil {
		return err
	}
	if c == nil {
		return errAdminNotFound
	}
	if c.Status == StatusExhausted {
		return errAdminExhausted
	}
	if c.Status != StatusRevoked {
		return nil // 幂等
	}
	now := nowMs()
	if c.Type == TypeMonth && c.ActivatedAt.Valid && c.ExpireAt.Valid && c.ExpireAt.Int64 <= now {
		return errAdminExpired
	}
	if err := restoreCodeByID(s.db, id); err != nil {
		return err
	}
	if err := insertAdminAudit(s.db, actor, AdminActionRestore, "license_code", strconvI64(id), "", ip); err != nil {
		return errAdminAuditFailed
	}
	return nil
}

// 管理端内部错误哨兵。
var (
	errAdminNotFound    = fmt.Errorf("not found")
	errAdminExpired     = fmt.Errorf("expired")
	errAdminExhausted   = fmt.Errorf("exhausted")
	errAdminAuditFailed = fmt.Errorf("audit failed")
)

// handleAdminRevoke 停用。
func (s *Server) handleAdminRevoke(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	id, ok := parseIDPath(w, r)
	if !ok {
		s.writeAdminErr(w, adminCodeBadRequest, "非法兑换码 ID")
		return
	}
	var req struct {
		Reason string `json:"reason"`
	}
	if r.Body != nil {
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64*1024)).Decode(&req); err != nil && !errors.Is(err, io.EOF) {
			s.writeAdminErr(w, adminCodeBadRequest, "请求格式错误")
			return
		}
	}
	if err := s.doRevoke(id, req.Reason, "admin", adminClientIP(r)); err != nil {
		switch err {
		case errAdminNotFound:
			s.writeAdminErr(w, adminCodeNotFound, "兑换码不存在")
		default:
			s.writeAdminErr(w, adminCodeServer, "停用失败")
		}
		return
	}
	s.writeAdminOK(w, map[string]interface{}{"ok": true})
}

// handleAdminRestore 启用。
func (s *Server) handleAdminRestore(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	id, ok := parseIDPath(w, r)
	if !ok {
		s.writeAdminErr(w, adminCodeBadRequest, "非法兑换码 ID")
		return
	}
	if err := s.doRestore(id, "admin", adminClientIP(r)); err != nil {
		switch err {
		case errAdminNotFound:
			s.writeAdminErr(w, adminCodeNotFound, "兑换码不存在")
		case errAdminExpired:
			s.writeAdminErr(w, adminCodeConflict, "已过期码不可启用")
		case errAdminExhausted:
			s.writeAdminErr(w, adminCodeConflict, "历史已用尽码不可启用")
		case errAdminAuditFailed:
			s.writeAdminErr(w, adminCodeServer, "已启用，但审计写入失败：请刷新核对审计记录")
		default:
			s.writeAdminErr(w, adminCodeServer, "启用失败")
		}
		return
	}
	s.writeAdminOK(w, map[string]interface{}{"ok": true})
}

// handleAdminBatch 批量停用/启用。
func (s *Server) handleAdminBatch(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	var req struct {
		IDs    []int64 `json:"ids"`
		Action string  `json:"action"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 256*1024)).Decode(&req); err != nil {
		s.writeAdminErr(w, adminCodeBadRequest, "请求格式错误")
		return
	}
	if len(req.IDs) == 0 || len(req.IDs) > 100 {
		s.writeAdminErr(w, adminCodeBadRequest, "一次最多操作 100 条")
		return
	}
	if req.Action != "revoke" && req.Action != "restore" {
		s.writeAdminErr(w, adminCodeBadRequest, "action 只能是 revoke 或 restore")
		return
	}
	ip := adminClientIP(r)
	failed := make([]map[string]interface{}, 0)
	okCount := 0
	for _, id := range req.IDs {
		var err error
		if req.Action == "revoke" {
			err = s.doRevoke(id, "后台批量停用", "admin", ip)
		} else {
			err = s.doRestore(id, "admin", ip)
		}
		if err != nil {
			msg := "操作失败"
			if err == errAdminNotFound {
				msg = "兑换码不存在"
			} else if err == errAdminExpired {
				msg = "已过期码不可启用"
			} else if err == errAdminExhausted {
				msg = "历史已用尽码不可启用"
			}
			failed = append(failed, map[string]interface{}{"id": id, "message": msg})
			continue
		}
		okCount++
	}
	s.writeAdminOK(w, map[string]interface{}{"ok": okCount, "failed": failed})
}

// parseIDPath 解析路径参数 id。
func parseIDPath(w http.ResponseWriter, r *http.Request) (int64, bool) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		// 调用方可能未走 requireAdmin（此处已鉴权），统一写业务错误。
		return 0, false
	}
	return id, true
}

// codeExists 判断兑换码是否存在（404 语义用）。
func (s *Server) codeExists(id int64) bool {
	var one int
	err := s.db.QueryRow(`SELECT 1 FROM license_code WHERE id = ? LIMIT 1`, id).Scan(&one)
	return err == nil
}

// parseLogFilter 解析使用记录筛选。
func parseLogFilter(r *http.Request, codeID int64) LogFilter {
	q := r.URL.Query()
	page, _ := strconv.Atoi(q.Get("page"))
	size, _ := strconv.Atoi(q.Get("size"))
	return LogFilter{
		CodeID: codeID,
		Action: strings.TrimSpace(q.Get("action")),
		Result: strings.TrimSpace(q.Get("result")),
		From:   parseEpochQuery(q.Get("from")),
		To:     parseEpochQuery(q.Get("to")),
		Page:   page,
		Size:   size,
	}
}

// handleAdminLogs 单码使用记录。
func (s *Server) handleAdminLogs(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	id, ok := parseIDPath(w, r)
	if !ok {
		s.writeAdminErr(w, adminCodeBadRequest, "非法兑换码 ID")
		return
	}
	if !s.codeExists(id) {
		s.writeAdminErr(w, adminCodeNotFound, "兑换码不存在")
		return
	}
	f := parseLogFilter(r, id)
	items, total, err := listLogs(s.db, f)
	if err != nil {
		s.writeAdminErr(w, adminCodeServer, "查询使用记录失败")
		return
	}
	page, size := normalizePage(f.Page, f.Size)
	s.writeAdminOK(w, map[string]interface{}{
		"items": items, "total": total, "page": page, "size": size,
		"totalPages": totalPages(total, size),
	})
}

// handleAdminCodesExport 导出当前筛选结果 CSV（≤10000）。
func (s *Server) handleAdminCodesExport(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	f := parseCodeFilter(r)
	f.Page, f.Size = 1, 500
	all := make([]CodeListItem, 0, 500)
	var total int64
	for len(all) < adminExportMax {
		items, t, err := listCodes(s.db, f)
		if err != nil {
			s.writeAdminErr(w, adminCodeServer, "导出查询失败")
			return
		}
		total = t
		all = append(all, items...)
		if int64(len(all)) >= total || len(items) == 0 {
			break
		}
		f.Page++
	}
	if len(all) > adminExportMax {
		all = all[:adminExportMax]
	}
	if int64(len(all)) < total {
		// 2026-11-06 审查：超过上限时显式告知（前端读该头提示缩小范围）。
		w.Header().Set("X-Export-Truncated", "true")
		w.Header().Set("X-Export-Total", strconvI64(total))
	}
	writeCSV(w, "codes-"+time.Now().Format("20060102")+".csv", codeCSVRows(all))
}

// codeCSVRows 兑换码 CSV 行（首行为表头）。
func codeCSVRows(items []CodeListItem) [][]string {
	rows := [][]string{{"code", "type", "tier", "role", "status", "usage", "boundDevice",
		"activatedAt", "expireAt", "remainDays", "remark", "createdAt"}}
	for _, it := range items {
		rows = append(rows, []string{
			it.Code, it.Type, strconvI64(it.Tier), strconv.Itoa(it.Role), it.Status, it.Usage,
			it.BoundDevice, fmtTimeLocal(it.ActivatedAt), fmtTimeLocal(it.ExpireAt),
			strconvI64(it.RemainDays), it.Remark, fmtTimeLocal(it.CreatedAt),
		})
	}
	return rows
}

// handleAdminLogsExport 导出单码使用记录 CSV（≤10000）。
func (s *Server) handleAdminLogsExport(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	id, ok := parseIDPath(w, r)
	if !ok {
		s.writeAdminErr(w, adminCodeBadRequest, "非法兑换码 ID")
		return
	}
	if !s.codeExists(id) {
		s.writeAdminErr(w, adminCodeNotFound, "兑换码不存在")
		return
	}
	f := parseLogFilter(r, id)
	f.Page, f.Size = 1, 500
	all := make([]LogItem, 0, 500)
	var total int64
	for len(all) < adminExportMax {
		items, t, err := listLogs(s.db, f)
		if err != nil {
			s.writeAdminErr(w, adminCodeServer, "导出查询失败")
			return
		}
		total = t
		all = append(all, items...)
		if int64(len(all)) >= total || len(items) == 0 {
			break
		}
		f.Page++
	}
	if len(all) > adminExportMax {
		all = all[:adminExportMax]
	}
	if int64(len(all)) < total {
		w.Header().Set("X-Export-Truncated", "true")
		w.Header().Set("X-Export-Total", strconvI64(total))
	}
	rows := [][]string{{"createdAt", "action", "resultCode", "resultText", "deviceLabel",
		"deviceHash", "ip", "ua"}}
	for _, it := range all {
		rows = append(rows, []string{fmtTimeLocal(it.CreatedAt), it.Action,
			strconv.Itoa(it.ResultCode), it.ResultText, it.DeviceLabel, it.DeviceHash, it.IP, it.UA})
	}
	writeCSV(w, fmt.Sprintf("code-%d-logs-%s.csv", id, time.Now().Format("20060102")), rows)
}

// csvSafeCell CSV 公式注入防护（S7）：以 = + - @ 开头的单元格前置单引号，Excel 按文本处理。
func csvSafeCell(v string) string {
	if len(v) > 0 && (v[0] == '=' || v[0] == '+' || v[0] == '-' || v[0] == '@') {
		return "'" + v
	}
	return v
}

// writeCSV 输出 UTF-8 BOM CSV（单元格做公式注入防护）。
func writeCSV(w http.ResponseWriter, filename string, rows [][]string) {
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", `attachment; filename="`+filename+`"`)
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte("\xEF\xBB\xBF"))
	for i := range rows {
		for j := range rows[i] {
			rows[i][j] = csvSafeCell(rows[i][j])
		}
	}
	wr := csv.NewWriter(w)
	_ = wr.WriteAll(rows)
}

// ---------------------------------------------------------------------------
// 功能目录与矩阵
// ---------------------------------------------------------------------------

// handleAdminFeatures 功能目录（只读）。
func (s *Server) handleAdminFeatures(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	items, err := loadCatalog(s.db)
	if err != nil {
		s.writeAdminErr(w, adminCodeServer, "查询功能目录失败")
		return
	}
	s.writeAdminOK(w, map[string]interface{}{"items": items})
}

// handleAdminMatrix 矩阵视图。
func (s *Server) handleAdminMatrix(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	rows, err := buildFeatureRows(s.db)
	if err != nil {
		s.writeAdminErr(w, adminCodeServer, "查询权限矩阵失败")
		return
	}
	s.writeAdminOK(w, map[string]interface{}{"roles": []int{1, 2, 3, RoleGM}, "rows": rows})
}

// handleAdminMatrixSave 保存矩阵（T1/T2/T3 全量）。
func (s *Server) handleAdminMatrixSave(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	var req MatrixUpdateReq
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 512*1024)).Decode(&req); err != nil {
		s.writeAdminErr(w, adminCodeBadRequest, "请求格式错误")
		return
	}
	// 审计与保存同事务（S6）；失败返回 4001 且矩阵未变更。
	diffs, err := saveMatrixAudited(s.db, req.Rows, "admin", adminClientIP(r))
	if err != nil {
		s.writeAdminErr(w, adminCodeBadRequest, err.Error())
		return
	}
	s.writeAdminOK(w, map[string]interface{}{"ok": true, "changes": len(diffs)})
}

// handleAdminMatrixReset 恢复默认矩阵。
func (s *Server) handleAdminMatrixReset(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	diffs, err := resetMatrixAudited(s.db, "admin", adminClientIP(r))
	if err != nil {
		s.writeAdminErr(w, adminCodeServer, "恢复默认矩阵失败："+err.Error())
		return
	}
	s.writeAdminOK(w, map[string]interface{}{"ok": true, "changes": len(diffs)})
}

// ---------------------------------------------------------------------------
// 审计
// ---------------------------------------------------------------------------

// handleAdminAudit 审计分页。
func (s *Server) handleAdminAudit(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	q := r.URL.Query()
	page, _ := strconv.Atoi(q.Get("page"))
	size, _ := strconv.Atoi(q.Get("size"))
	items, total, err := listAudit(s.db, page, size)
	if err != nil {
		s.writeAdminErr(w, adminCodeServer, "查询审计失败")
		return
	}
	page, size = normalizePage(page, size)
	s.writeAdminOK(w, map[string]interface{}{
		"items": items, "total": total, "page": page, "size": size,
		"totalPages": totalPages(total, size),
	})
}
