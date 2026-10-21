-- 柠檬百宝箱同步中枢 · D1 schema
-- 应用方式：逐条 POST /accounts/{account_id}/d1/database/{uuid}/query
-- 幂等：全部 IF NOT EXISTS，可重复执行。

-- 单调序号发号器：push 时用 UPDATE ... RETURNING 一次领一批连续号
CREATE TABLE IF NOT EXISTS meta (
  k TEXT PRIMARY KEY,
  v INTEGER NOT NULL
);
INSERT OR IGNORE INTO meta(k, v) VALUES ('seq', 0);

-- 记录权威表：一条 (collection, record_key) 一行。
-- payload 是该实体的 Snapshot JSON（BackupSnapshots.kt 的 DTO），
-- record_key 用 Snapshot.syncId —— 绝不用本机自增 id，否则跨设备外键会指错行。
CREATE TABLE IF NOT EXISTS records (
  collection      TEXT    NOT NULL,
  record_key      TEXT    NOT NULL,
  payload         TEXT    NOT NULL,
  server_seq      INTEGER NOT NULL,
  updated_by_user TEXT    NOT NULL,  -- 人（显示「这条最后是谁改的」）
  updated_by_dev  TEXT    NOT NULL,  -- 设备（pull 时排除自己；同人多设备互不误排）
  updated_at      INTEGER NOT NULL,
  deleted         INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (collection, record_key)
);
CREATE INDEX IF NOT EXISTS idx_records_seq ON records(server_seq);
CREATE INDEX IF NOT EXISTS idx_records_coll_seq ON records(collection, server_seq);
CREATE INDEX IF NOT EXISTS idx_records_dev_seq ON records(updated_by_dev, server_seq);
-- 快照接口按「集合 + 活数据 + 序号」翻页；没有它时 WHERE deleted = 0 只能逐行过滤。
CREATE INDEX IF NOT EXISTS idx_records_coll_live_seq ON records(collection, deleted, server_seq);

-- 实例标识：删库/重建后随机生成一次。客户端把它当作「服务端身份」，
-- 一旦变化就知道本地水位和缓存全部失效，需要重新快照/回灌本机数据。
CREATE TABLE IF NOT EXISTS sync_meta (
  k TEXT PRIMARY KEY,
  v TEXT NOT NULL
);
INSERT OR IGNORE INTO sync_meta(k, v) VALUES ('instance_id', lower(hex(randomblob(16))));

-- 账号：只在服务端配置，客户端无法注册
CREATE TABLE IF NOT EXISTS users (
  id         TEXT PRIMARY KEY,
  name       TEXT NOT NULL UNIQUE,
  salt       TEXT NOT NULL,
  iterations INTEGER NOT NULL,
  hash       TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

-- 会话：库里只存 token 的 sha256，泄库也拿不到可用 token
CREATE TABLE IF NOT EXISTS sessions (
  token_hash  TEXT PRIMARY KEY,
  user_id     TEXT NOT NULL,
  device_id   TEXT NOT NULL,
  device_name TEXT NOT NULL DEFAULT '',
  expires_at  INTEGER NOT NULL,
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);

-- 设备：对账页要展示「谁、什么时候、同步到哪一号」
CREATE TABLE IF NOT EXISTS devices (
  device_id     TEXT PRIMARY KEY,
  user_id       TEXT,
  name          TEXT,
  last_seen_at  INTEGER,
  last_pull_seq INTEGER NOT NULL DEFAULT 0
);

-- 冲突台账：baseSeq 落后的条目记在这里，绝不静默覆盖
CREATE TABLE IF NOT EXISTS conflicts (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  collection TEXT,
  record_key TEXT,
  base_seq   INTEGER,
  server_seq INTEGER,
  by_device  TEXT,
  at         INTEGER
);
CREATE INDEX IF NOT EXISTS idx_conflicts_at ON conflicts(at);

-- 登录失败限流：按 IP / 账号两个桶计数，窗口内失败过多短暂锁定。
-- 表缺失时 worker 会放行（见 worker.js 的 safeLoginBlockedSeconds），不会把所有人挡在门外。
CREATE TABLE IF NOT EXISTS login_failures (
  bucket        TEXT PRIMARY KEY,
  failures      INTEGER NOT NULL DEFAULT 0,
  first_at      INTEGER NOT NULL,
  last_at       INTEGER NOT NULL,
  blocked_until INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_login_failures_last ON login_failures(last_at);
