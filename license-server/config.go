package main

import (
	"flag"
	"os"
	"strconv"
)

// Config 服务端运行时配置。来源优先级：命令行 flag > 环境变量 > 内置默认值。
type Config struct {
	// DBPath SQLite 数据库路径（env LICENSE_DB，默认 ./license.db）
	DBPath string
	// Secret 请求签名密钥（env LICENSE_SECRET，serve 时必须设置）
	Secret string
	// AdminToken 管理令牌（env LICENSE_ADMIN_TOKEN，rebind 时校验）
	AdminToken string
	// Addr HTTP 监听地址（env LICENSE_ADDR，默认 127.0.0.1:8787）
	Addr string
	// RebindMax 换绑次数上限（env LICENSE_REBIND_MAX，默认 0 = 禁止换绑；
	// >0 = 每码限 N 次；-1 = 不限。默认禁止是对外承诺：一码一次激活不换绑）
	RebindMax int64
	// NonceTTLMs nonce 防重放记录存活毫秒数（env LICENSE_NONCE_TTL 为秒，默认 900s）
	NonceTTLMs int64
}

// envOr 读取环境变量，为空时返回默认值。
func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

// envInt64 读取环境变量并解析为 int64，解析失败或为空时返回默认值。
func envInt64(key string, def int64) int64 {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil {
			return n
		}
	}
	return def
}

// defaultConfig 基于环境变量构造默认配置。
func defaultConfig() *Config {
	return &Config{
		DBPath:     envOr("LICENSE_DB", "./license.db"),
		Secret:     os.Getenv("LICENSE_SECRET"),
		AdminToken: os.Getenv("LICENSE_ADMIN_TOKEN"),
		Addr:       envOr("LICENSE_ADDR", "127.0.0.1:8787"),
		RebindMax:  envInt64("LICENSE_REBIND_MAX", RebindMaxDefault),
		NonceTTLMs: envInt64("LICENSE_NONCE_TTL", NonceTTLDefaultSec) * 1000,
	}
}

// registerDBFlag 为子命令注册通用的 --db 参数（默认取环境变量值）。
func registerDBFlag(fs *flag.FlagSet, c *Config) {
	fs.StringVar(&c.DBPath, "db", c.DBPath, "SQLite 数据库路径（env LICENSE_DB，默认 ./license.db）")
}
