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
    -- 授权等级（批次7 三级权限 2026-10-23）：
    --   1 = 普通（仅分身） / 2 = 会员（日常脚本） / 3 = 高级会员（高阶功能）
    -- 存量码与未指定等级的新码一律 3（DEFAULT 3），避免伤害现有用户。
    -- 旧库迁移：db.go ensureColumn("license_code","tier",...) 幂等补列（ALTER TABLE ADD COLUMN）。
    tier          INTEGER NOT NULL DEFAULT 3 CHECK (tier IN (1,2,3)),
    duration_days INTEGER NOT NULL DEFAULT 30,             -- month=30；permanent 忽略
    status        TEXT    NOT NULL DEFAULT 'active'        -- active | revoked | exhausted
                          CHECK (status IN ('active','revoked','exhausted')),
    remark        TEXT    NOT NULL DEFAULT '',
    created_at    INTEGER NOT NULL,                        -- epoch ms UTC
    activated_at  INTEGER,                                 -- 首次激活时间（权威，R2 起算点）
    expire_at     INTEGER,                                 -- 到期时间；permanent 为 NULL
    revoked_at    INTEGER,
    revoke_reason TEXT,
    created_by    TEXT    NOT NULL DEFAULT '',
    -- GM 标记（2026-11-06 管理后台）：逻辑第 4 档，1=GM。
    -- 用独立列而非扩展 tier CHECK，避免 SQLite 重建表；旧库由 db.go ensureColumn 幂等补列。
    is_gm         INTEGER NOT NULL DEFAULT 0
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

-- 功能目录（2026-11-06 管理后台）：以 App FeatureGate 的 38 个 key 为唯一事实源。
-- 服务端启动时按内置 seed upsert；管理后台只读展示，矩阵勾选以此为行集合。
CREATE TABLE IF NOT EXISTS feature_catalog (
    key          TEXT PRIMARY KEY,              -- 稳定 key（FeatureGate.Feature.key）
    label        TEXT    NOT NULL,              -- 中文名
    default_tier INTEGER NOT NULL,              -- 默认最低等级 1/2/3
    implemented  INTEGER NOT NULL DEFAULT 1,    -- 1=已实现；0=未实现（任何角色都不可放出）
    debug_only   INTEGER NOT NULL DEFAULT 0,    -- 1=仅调试包（release 恒隐藏）
    sort         INTEGER NOT NULL DEFAULT 0
);

-- 角色 → 功能可见性矩阵（2026-11-06 管理后台）。
-- role ∈ {1,2,3} 落库；GM(4) 恒为"已实现全集"，只读、不落库，避免与 is_gm 双写不一致。
CREATE TABLE IF NOT EXISTS role_feature (
    role        INTEGER NOT NULL CHECK (role IN (1,2,3)),
    feature_key TEXT    NOT NULL,
    visible     INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (role, feature_key)
);
CREATE INDEX IF NOT EXISTS idx_role_feature_role ON role_feature(role, visible);

-- 管理后台操作审计（2026-11-06）：只增不删，记录每次写操作与矩阵 diff。
CREATE TABLE IF NOT EXISTS admin_audit (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at  INTEGER NOT NULL,
    actor       TEXT    NOT NULL DEFAULT 'admin',
    action      TEXT    NOT NULL,               -- login/generate/revoke/restore/matrix_save/matrix_reset
    target_type TEXT    NOT NULL DEFAULT '',
    target_id   TEXT    NOT NULL DEFAULT '',
    detail      TEXT    NOT NULL DEFAULT '',    -- JSON（参数/diff）
    ip          TEXT    NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_audit_time ON admin_audit(created_at);
