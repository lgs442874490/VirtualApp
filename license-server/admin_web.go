package main

import (
	"embed"
	"io"
	"io/fs"
	"net/http"
	"path"
	"strings"
)

// ---------------------------------------------------------------------------
// 管理后台静态页（2026-11-06）
//
// 页面随二进制 go:embed 打包，不依赖外部静态文件同步；/admin 无尾斜杠时重定向到
// /admin/，保证 index.html 内相对路径（./app.js、./styles.css）解析正确。
// ---------------------------------------------------------------------------

//go:embed admin/static/*
var adminStaticFiles embed.FS

// handleAdminWeb 提供 /admin 页面与静态资源。
func (s *Server) handleAdminWeb(w http.ResponseWriter, r *http.Request) {
	if !s.adminConfigured() {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = io.WriteString(w, adminNotConfiguredHTML)
		return
	}
	if r.URL.Path == "/admin" {
		http.Redirect(w, r, "/admin/", http.StatusMovedPermanently)
		return
	}
	rel := strings.TrimPrefix(r.URL.Path, "/admin/")
	if rel == "" {
		rel = "index.html"
	}
	// 防目录穿越：只允许普通文件名/子路径，拒绝 .. 与绝对路径。
	clean := path.Clean("/" + rel)
	if strings.Contains(clean, "..") || clean == "/" {
		http.NotFound(w, r)
		return
	}
	rel = strings.TrimPrefix(clean, "/")

	sub, err := fs.Sub(adminStaticFiles, "admin/static")
	if err != nil {
		http.NotFound(w, r)
		return
	}
	data, err := fs.ReadFile(sub, rel)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", adminContentType(rel))
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	// 安全头（2026-11-06 代码审查）：后台页面限同源资源、禁被嵌套，降低 XSS/点击劫持面。
	w.Header().Set("Content-Security-Policy",
		"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "+
			"img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'")
	w.Header().Set("X-Frame-Options", "DENY")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

// adminContentType 按扩展名返回 Content-Type。
func adminContentType(name string) string {
	switch {
	case strings.HasSuffix(name, ".html"):
		return "text/html; charset=utf-8"
	case strings.HasSuffix(name, ".js"):
		return "application/javascript; charset=utf-8"
	case strings.HasSuffix(name, ".css"):
		return "text/css; charset=utf-8"
	case strings.HasSuffix(name, ".svg"):
		return "image/svg+xml"
	case strings.HasSuffix(name, ".png"):
		return "image/png"
	default:
		return "application/octet-stream"
	}
}

// adminNotConfiguredHTML 未配置口令时的提示页（不含任何敏感信息）。
const adminNotConfiguredHTML = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>管理后台未启用</title>
<style>body{font-family:system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;
background:#f8fafc;color:#334155;display:flex;align-items:center;justify-content:center;
min-height:100vh;margin:0}div{background:#fff;border:1px solid #e5e7eb;border-radius:12px;
padding:28px 32px;max-width:520px;box-shadow:0 8px 24px rgba(15,23,42,.06)}
h1{font-size:18px;margin:0 0 10px}p{font-size:14px;line-height:1.7;margin:6px 0}
code{background:#f1f5f9;border-radius:4px;padding:1px 5px}</style></head>
<body><div><h1>管理后台未启用</h1>
<p>服务端未配置管理员口令，后台入口已关闭。</p>
<p>请在部署环境设置 <code>LICENSE_ADMIN_PASSWORD</code>（至少 16 个字符）后重启 license-server。</p>
</div></body></html>`
