package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"
)

// logWarn 统一的告警日志前缀（普通日志直接用标准库 log）。
func logWarn(format string, args ...interface{}) {
	log.Printf("[warn] "+format, args...)
}

// strconvI64 int64 → 十进制字符串。
func strconvI64(v int64) string {
	return strconv.FormatInt(v, 10)
}

// usage 打印用法。
func usage() {
	fmt.Fprintf(os.Stderr, `license-server —— VAContainer 兑换码授权服务端

用法:
  license-cli <子命令> [参数...]

子命令:
  initdb    初始化数据库（幂等）
  serve     启动 HTTP 服务（需 LICENSE_SECRET）
  issue     批量发码
  revoke    吊销兑换码
  list      列出兑换码
  rebind    换绑设备（需 admin-token）

环境变量:
  LICENSE_DB            SQLite 数据库路径（默认 ./license.db）
  LICENSE_SECRET        请求签名密钥（serve 必填）
  LICENSE_ADMIN_TOKEN   管理令牌（rebind 鉴权）
  LICENSE_ADDR          监听地址（默认 127.0.0.1:8787）
  LICENSE_REBIND_MAX    换绑次数上限，0=不限（默认 1，每码限 1 次）
  LICENSE_NONCE_TTL     nonce 存活秒数（默认 900）

示例:
  license-cli initdb
  LICENSE_SECRET=devsecret license-cli serve
  license-cli issue --type month --count 5 --remark "首批" --out codes.csv
  license-cli list --status active
  license-cli revoke --code XXXX-XXXX-XXXX-XXXX --reason "refund"
  LICENSE_ADMIN_TOKEN=devtoken license-cli rebind --code XXXX-... --new-device-hash <hash>
`)
}

// main 子命令分发入口。
func main() {
	log.SetFlags(log.LstdFlags)
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	cmd := os.Args[1]
	args := os.Args[2:]

	var err error
	switch cmd {
	case "initdb":
		err = cmdInitDB(args)
	case "serve":
		err = cmdServe(args)
	case "issue":
		err = cmdIssue(args)
	case "revoke":
		err = cmdRevoke(args)
	case "list":
		err = cmdList(args)
	case "rebind":
		err = cmdRebindCLI(args)
	case "-h", "--help", "help":
		usage()
		return
	default:
		usage()
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "错误: %v\n", err)
		os.Exit(1)
	}
}

// cmdServe 启动 HTTP 服务。
func cmdServe(args []string) error {
	cfg := defaultConfig()
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	registerDBFlag(fs, cfg)
	fs.StringVar(&cfg.Secret, "secret", cfg.Secret, "请求签名密钥（env LICENSE_SECRET）")
	fs.StringVar(&cfg.AdminToken, "admin-token", cfg.AdminToken, "管理令牌（env LICENSE_ADMIN_TOKEN）")
	fs.StringVar(&cfg.Addr, "addr", cfg.Addr, "监听地址（env LICENSE_ADDR，默认 127.0.0.1:8787）")
	fs.Int64Var(&cfg.RebindMax, "rebind-max", cfg.RebindMax, "换绑次数上限，默认 1，0=不限（env LICENSE_REBIND_MAX）")
	fs.Int64Var(&cfg.NonceTTLMs, "nonce-ttl-ms", cfg.NonceTTLMs, "nonce 存活毫秒数（env LICENSE_NONCE_TTL 为秒）")
	if err := fs.Parse(args); err != nil {
		return err
	}

	// 密钥强校验：绝不允许用空密钥静默启动。
	if cfg.Secret == "" {
		return errors.New("LICENSE_SECRET 未设置：服务端必须配置签名密钥才能启动 serve\n" +
			"  请执行：export LICENSE_SECRET='<你的密钥>'   或   使用 --secret 参数")
	}
	if len(cfg.Secret) < 16 {
		logWarn("LICENSE_SECRET 长度较短（%d），建议使用 ≥32 字节的随机串", len(cfg.Secret))
	}
	if cfg.AdminToken == "" {
		logWarn("LICENSE_ADMIN_TOKEN 未设置：/v1/rebind 与 CLI rebind 将无法通过鉴权")
	}
	if cfg.NonceTTLMs <= SignWindowMs {
		logWarn("NonceTTL(%dms) 不大于签名窗口(%dms)，建议调大以避免签名窗口内的 nonce 被提前清理", cfg.NonceTTLMs, SignWindowMs)
	}

	db, err := openDB(cfg.DBPath)
	if err != nil {
		return err
	}
	defer db.Close()

	srv := NewServer(db, cfg)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// nonce TTL 清理循环
	go srv.cleanupLoop(ctx)

	httpSrv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           srv.Mux(),
		ReadHeaderTimeout: 5 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		log.Printf("[info] license-server 启动，监听 %s，DB=%s", cfg.Addr, cfg.DBPath)
		if err := httpSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
	}()

	select {
	case <-ctx.Done():
		log.Printf("[info] 收到退出信号，正在优雅关闭…")
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := httpSrv.Shutdown(shutdownCtx); err != nil {
			logWarn("优雅关闭失败: %v", err)
		}
		return nil
	case err := <-errCh:
		return fmt.Errorf("HTTP 服务异常退出: %w", err)
	}
}
