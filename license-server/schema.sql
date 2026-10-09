-- VAContainer 授权服务端建表脚本（设计文档 §4.1，字段名照抄，勿随意改动）
-- 由 db.go 通过 //go:embed 嵌入二进制，initdb 幂等执行。

PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

-- 兑换码主表
CREATE TABLE IF NOT EXISTS license_code (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    code          TEXT    NOT NULL UNIQUE,                 -- 展示码，形如 XXXX-XXXX-XXXX-XXXX
    code_hash     TEXT    NOT NULL UNIQUE,                 -- SHA-256(code) 用于索引/日志脱敏
    type          TEXT    NOT NULL CHECK (type IN ('month','permanent')),
    duration_days INTEGER NOT NULL DEFAULT 30,             -- month=30；permanent 忽略
    status        TEXT    NOT NULL DEFAULT 'active'        -- active | revoked | exhausted
                          CHECK (status IN ('active','revoked','exhausted')),
    remark        TEXT    NOT NULL DEFAULT '',
    created_at    INTEGER NOT NULL,                        -- epoch ms UTC
    activated_at  INTEGER,                                 -- 首次激活时间（权威，R2 起算点）
    expire_at     INTEGER,                                 -- 到期时间；permanent 为 NULL
    revoked_at    INTEGER,
    revoke_reason TEXT,
    created_by    TEXT    NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_code_status  ON license_code(status);
CREATE INDEX IF NOT EXISTS idx_code_expire  ON license_code(expire_at);

-- 设备绑定表（一码一机）
CREATE TABLE IF NOT EXISTS device_binding (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    code_id      INTEGER NOT NULL REFERENCES license_code(id) ON DELETE CASCADE,
    device_hash  TEXT    NOT NULL,                         -- 客户端上传的加盐指纹
    device_label TEXT    NOT NULL DEFAULT '',              -- 机型等非敏感标识（运营可见）
    status       TEXT    NOT NULL DEFAULT 'active'         -- active | unbound
                         CHECK (status IN ('active','unbound')),
    bound_at     INTEGER NOT NULL,
    last_seen_at INTEGER,
    last_verify_at INTEGER,
    verify_count INTEGER NOT NULL DEFAULT 0,
    UNIQUE(code_id, device_hash)
);
CREATE INDEX IF NOT EXISTS idx_bind_code ON device_binding(code_id, status);

-- 激活/校验流水表
CREATE TABLE IF NOT EXISTS verify_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    code_id     INTEGER REFERENCES license_code(id) ON DELETE SET NULL,
    device_hash TEXT    NOT NULL DEFAULT '',
    action      TEXT    NOT NULL,                          -- activate | verify | rebind | revoke
    result_code INTEGER NOT NULL,                          -- 业务返回码（0=成功）
    ip          TEXT    NOT NULL DEFAULT '',
    ua          TEXT    NOT NULL DEFAULT '',
    created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_log_code ON verify_log(code_id, created_at);

-- 防重放 nonce（TTL 清理）
CREATE TABLE IF NOT EXISTS used_nonce (
    nonce     TEXT    PRIMARY KEY,
    used_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_nonce_time ON used_nonce(used_at);
