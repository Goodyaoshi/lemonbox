/**
 * 柠檬百宝箱 · 同步中枢（Cloudflare Worker 单文件实现）
 *
 * 三个职责，同一份部署：
 *   1. /v1/*        —— 登录 + D1 权威记录同步（server_seq 单调序号取代墙钟与水位线）
 *   2. /v1/blob/*   —— 自建 S3 兼容存储（Filebase）的密钥只在这里；客户端每次只拿几分钟有效的预签名 URL
 *   3. /mcp         —— MCP 服务端，读写、禁删，供 AI 整理家庭数据
 *
 * 刻意不用 npm / wrangler / 打包：单文件 ES module，直接经 Cloudflare API 上传。
 * 刻意不做的事：不下发长期密钥、不提供删除授权、不用墙钟判胜负。
 *
 * secrets（只存在于 Worker，绝不进仓库与 APK）：
 *   S3_ENDPOINT / S3_REGION / S3_BUCKET / S3_PREFIX / S3_ACCESS_KEY / S3_SECRET_KEY
 *   ADMIN_TOKEN / MCP_TOKEN
 * 绑定：DB（D1）
 */

const VERSION = "1.0.0";
const PBKDF2_ITERATIONS = 100000;
const SESSION_TTL_MS = 90 * 24 * 60 * 60 * 1000;
const PUSH_MAX_CHANGES = 200;
const PULL_MAX_LIMIT = 500;
const PRESIGN_MAX_TTL = 600;
const PRESIGN_DEFAULT_TTL = 300;
/** 空请求体的 SHA-256（GET / HEAD / DELETE 用）。 */
const EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
const ALGORITHM = "AWS4-HMAC-SHA256";

/* ------------------------------------------------------------------ 基础工具 */

const enc = new TextEncoder();

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

function fail(message, status = 400) {
  return json({ error: message }, status);
}

function hex(buffer) {
  return [...new Uint8Array(buffer)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function hexToBytes(value) {
  const out = new Uint8Array(value.length / 2);
  for (let i = 0; i < out.length; i += 1) {
    out[i] = parseInt(value.substr(i * 2, 2), 16);
  }
  return out;
}

async function sha256Hex(text) {
  return hex(await crypto.subtle.digest("SHA-256", enc.encode(text)));
}

function randomToken() {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return hex(bytes);
}

/** 定长比较，避免按字符提前返回泄漏信息。 */
function equalConstantTime(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

async function hmacSha256(key, data) {
  const rawKey = typeof key === "string" ? enc.encode(key) : key;
  const imported = await crypto.subtle.importKey(
    "raw",
    rawKey,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return crypto.subtle.sign("HMAC", imported, typeof data === "string" ? enc.encode(data) : data);
}

async function pbkdf2Hex(password, saltHex, iterations) {
  const base = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, [
    "deriveBits",
  ]);
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", salt: hexToBytes(saltHex), iterations, hash: "SHA-256" },
    base,
    256,
  );
  return hex(bits);
}

function chunk(list, size) {
  const out = [];
  for (let i = 0; i < list.length; i += size) out.push(list.slice(i, i + size));
  return out;
}

/* ------------------------------------------------------------ SigV4（对象存储） */

/**
 * S3 URI 编码：仅 A-Za-z0-9-_.~ 原样保留，其余按 UTF-8 字节百分号编码。
 * 路径里保留 `/`、查询串里编码 `/` —— 与客户端既有实现同一套规则。
 */
function uriEncode(value, encodeSlash) {
  let out = "";
  for (const byte of enc.encode(String(value))) {
    const ch = String.fromCharCode(byte);
    const unreserved =
      (byte >= 0x41 && byte <= 0x5a) ||
      (byte >= 0x61 && byte <= 0x7a) ||
      (byte >= 0x30 && byte <= 0x39) ||
      ch === "-" ||
      ch === "_" ||
      ch === "." ||
      ch === "~";
    if (unreserved) out += ch;
    else if (ch === "/" && !encodeSlash) out += "/";
    else out += `%${byte.toString(16).toUpperCase().padStart(2, "0")}`;
  }
  return out;
}

function amzDateNow() {
  return new Date().toISOString().slice(0, 19).replace(/[-:]/g, "") + "Z";
}

async function signingKey(secret, dateStamp, region) {
  const kDate = await hmacSha256(`AWS4${secret}`, dateStamp);
  const kRegion = await hmacSha256(kDate, region);
  const kService = await hmacSha256(kRegion, "s3");
  return hmacSha256(kService, "aws4_request");
}

function canonicalQueryString(query) {
  return Object.keys(query)
    .sort()
    .map((k) => `${uriEncode(k, true)}=${uriEncode(query[k], true)}`)
    .join("&");
}

/** path-style 对象路径：`/<bucket>/<prefix>/<key>`（Filebase / MinIO / OSS / COS 都支持）。 */
function objectPath(env, key) {
  const prefix = String(env.S3_PREFIX || "").replace(/^\/+|\/+$/g, "");
  const full = prefix ? `${prefix}/${key}` : key;
  return `/${env.S3_BUCKET}/${uriEncode(full, false)}`;
}

function endpointHost(env) {
  return new URL(env.S3_ENDPOINT).host;
}

/**
 * 生成预签名 URL（query 式签名，UNSIGNED-PAYLOAD，只签 host）。
 * 与客户端既有的 S3Signer.presignedUrl 同一套语义 —— 那边已用官方测试向量验证过。
 */
async function presignUrl(env, key, method, ttlSeconds) {
  const host = endpointHost(env);
  const region = env.S3_REGION || "auto";
  const amzDate = amzDateNow();
  const dateStamp = amzDate.slice(0, 8);
  const scope = `${dateStamp}/${region}/s3/aws4_request`;
  const canonicalPath = objectPath(env, key);
  const query = {
    "X-Amz-Algorithm": ALGORITHM,
    "X-Amz-Credential": `${env.S3_ACCESS_KEY}/${scope}`,
    "X-Amz-Date": amzDate,
    "X-Amz-Expires": String(ttlSeconds),
    "X-Amz-SignedHeaders": "host",
  };
  const canonicalQuery = canonicalQueryString(query);
  const canonicalRequest = [
    method,
    canonicalPath,
    canonicalQuery,
    `host:${host}\n`,
    "host",
    "UNSIGNED-PAYLOAD",
  ].join("\n");
  const stringToSign = [ALGORITHM, amzDate, scope, await sha256Hex(canonicalRequest)].join("\n");
  const signature = hex(
    await hmacSha256(await signingKey(env.S3_SECRET_KEY, dateStamp, region), stringToSign),
  );
  return {
    url: `https://${host}${canonicalPath}?${canonicalQuery}&X-Amz-Signature=${signature}`,
    expiresAt: Date.now() + ttlSeconds * 1000,
  };
}

/**
 * 以「请求头式签名」直连对象存储，用于客户端拿不到授权的动作：
 * 探在（HEAD）、删除（DELETE）、列举（GET + query）、连桶自检（HEAD 桶根）。
 *
 * 注意：Workers 的 fetch 不允许显式设置 Host 头（fetch 规范的禁止头），
 * 但实际发出的 Host 就是 URL 的 host，与参与签名的 host 一致，故无需也无法手动设置。
 */
async function signedS3Fetch(env, { method, key = "", query = null }) {
  const host = endpointHost(env);
  const region = env.S3_REGION || "auto";
  const amzDate = amzDateNow();
  const dateStamp = amzDate.slice(0, 8);
  const scope = `${dateStamp}/${region}/s3/aws4_request`;
  const canonicalPath = key ? objectPath(env, key) : `/${env.S3_BUCKET}`;
  const canonicalQuery = query ? canonicalQueryString(query) : "";

  // 参与签名的头：host + x-amz-content-sha256 + x-amz-date（键升序）。
  const canonicalHeaders =
    `host:${host}\n` +
    `x-amz-content-sha256:${EMPTY_SHA256}\n` +
    `x-amz-date:${amzDate}\n`;
  const signedHeaders = "host;x-amz-content-sha256;x-amz-date";
  const canonicalRequest = [
    method,
    canonicalPath,
    canonicalQuery,
    canonicalHeaders,
    signedHeaders,
    EMPTY_SHA256,
  ].join("\n");
  const stringToSign = [ALGORITHM, amzDate, scope, await sha256Hex(canonicalRequest)].join("\n");
  const signature = hex(
    await hmacSha256(await signingKey(env.S3_SECRET_KEY, dateStamp, region), stringToSign),
  );
  const authorization =
    `${ALGORITHM} Credential=${env.S3_ACCESS_KEY}/${scope}, ` +
    `SignedHeaders=${signedHeaders}, Signature=${signature}`;

  const url = `https://${host}${canonicalPath}${canonicalQuery ? `?${canonicalQuery}` : ""}`;
  return fetch(url, {
    method,
    headers: {
      "x-amz-content-sha256": EMPTY_SHA256,
      "x-amz-date": amzDate,
      Authorization: authorization,
      "Accept-Encoding": "identity",
    },
  });
}

/* ------------------------------------------------------------------ 会话与账号 */

async function requireSession(request, env) {
  const header = request.headers.get("Authorization") || "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  if (!token) return fail("未登录", 401);
  const tokenHash = await sha256Hex(token);
  const row = await env.DB.prepare(
    "SELECT s.user_id, s.device_id, s.device_name, s.expires_at, u.name AS user_name " +
      "FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?",
  )
    .bind(tokenHash)
    .first();
  if (!row) return fail("登录已失效，请重新登录", 401);
  if (row.expires_at < Date.now()) {
    await env.DB.prepare("DELETE FROM sessions WHERE token_hash = ?").bind(tokenHash).run();
    return fail("登录已过期，请重新登录", 401);
  }
  return {
    user_id: row.user_id,
    user_name: row.user_name,
    device_id: row.device_id,
    device_name: row.device_name,
  };
}

async function handleLogin(request, env) {
  const body = await request.json().catch(() => null);
  if (!body || !body.username || !body.password) return fail("缺少账号或密码");
  const username = String(body.username).trim();
  const deviceId = String(body.deviceId || "").trim();
  if (!deviceId) return fail("缺少设备标识");

  const user = await env.DB.prepare("SELECT * FROM users WHERE name = ?").bind(username).first();
  // 账号不存在与密码错误给同一句话，避免枚举出有哪些账号。
  if (!user) return fail("账号或密码不正确", 401);
  const hash = await pbkdf2Hex(String(body.password), user.salt, user.iterations);
  if (!equalConstantTime(hash, user.hash)) return fail("账号或密码不正确", 401);

  const token = randomToken();
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare(
      "INSERT INTO sessions(token_hash, user_id, device_id, device_name, expires_at, created_at) " +
        "VALUES(?,?,?,?,?,?)",
    ).bind(await sha256Hex(token), user.id, deviceId, String(body.deviceName || ""), now + SESSION_TTL_MS, now),
    env.DB.prepare(
      "INSERT INTO devices(device_id, user_id, name, last_seen_at, last_pull_seq) VALUES(?,?,?,?,0) " +
        "ON CONFLICT(device_id) DO UPDATE SET user_id=excluded.user_id, name=excluded.name, last_seen_at=excluded.last_seen_at",
    ).bind(deviceId, user.id, String(body.deviceName || ""), now),
  ]);

  return json({
    token,
    expiresAt: now + SESSION_TTL_MS,
    user: { id: user.id, name: user.name },
  });
}

async function handleLogout(request, env, session) {
  const header = request.headers.get("Authorization") || "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  if (token) {
    await env.DB.prepare("DELETE FROM sessions WHERE token_hash = ?")
      .bind(await sha256Hex(token))
      .run();
  }
  return json({ ok: true, user: session.user_name });
}

/** 账号只在服务端配置：建号 / 改密都走这条带 X-Admin-Token 的通道。 */
async function handleAdminUsers(request, env) {
  const provided = request.headers.get("X-Admin-Token") || "";
  if (!env.ADMIN_TOKEN || !equalConstantTime(provided, env.ADMIN_TOKEN)) {
    return fail("无权访问", 403);
  }
  const body = await request.json().catch(() => null);
  if (!body || !body.name || !body.password) return fail("缺少 name 或 password");
  const name = String(body.name).trim();
  if (name.length < 1 || name.length > 32) return fail("账号长度需在 1-32 之间");
  if (String(body.password).length < 6) return fail("密码至少 6 位");

  const salt = randomToken().slice(0, 32);
  const iterations = PBKDF2_ITERATIONS;
  const hash = await pbkdf2Hex(String(body.password), salt, iterations);
  const existing = await env.DB.prepare("SELECT id FROM users WHERE name = ?").bind(name).first();
  const id = existing ? existing.id : randomToken().slice(0, 32);
  await env.DB.prepare(
    "INSERT INTO users(id, name, salt, iterations, hash, created_at) VALUES(?,?,?,?,?,?) " +
      "ON CONFLICT(name) DO UPDATE SET salt=excluded.salt, iterations=excluded.iterations, hash=excluded.hash",
  )
    .bind(id, name, salt, iterations, hash, Date.now())
    .run();
  return json({ ok: true, user: { id, name }, created: !existing });
}

/* ------------------------------------------------------------------ 记录同步 */

async function handlePush(request, env, session) {
  const body = await request.json().catch(() => null);
  const changes = body && Array.isArray(body.changes) ? body.changes : [];
  if (!changes.length) return json({ applied: [], conflicts: [], hasMore: false });
  if (changes.length > PUSH_MAX_CHANGES) {
    return fail(`单次最多推送 ${PUSH_MAX_CHANGES} 条，请分批`);
  }
  for (const change of changes) {
    if (!change || !change.collection || !change.key || typeof change.payload !== "string") {
      return fail("变更条目缺少 collection / key / payload");
    }
  }

  // 1) 先读这些记录当前的服务端序号，判断是否有人在我之后改过。
  //    单条 SQL 绑定参数 ≤100，一条记录 2 个参数 → 每批 50 条。
  const existing = new Map();
  for (const part of chunk(changes, 50)) {
    const placeholders = part.map(() => "(?,?)").join(",");
    const binds = part.flatMap((c) => [c.collection, c.key]);
    const rows = await env.DB.prepare(
      "SELECT collection, record_key, server_seq, payload, updated_by_user FROM records " +
        `WHERE (collection, record_key) IN (${placeholders})`,
    )
      .bind(...binds)
      .all();
    for (const row of rows.results || []) {
      existing.set(`${row.collection}\u0000${row.record_key}`, row);
    }
  }

  // 2) 一次领一批连续序号（RETURNING 拿回发完后的值，倒推起始号）。
  const count = changes.length;
  const meta = await env.DB.prepare("UPDATE meta SET v = v + ? WHERE k = 'seq' RETURNING v")
    .bind(count)
    .first();
  const firstSeq = (meta && Number(meta.v) ? Number(meta.v) : 0) - count + 1;

  const now = Date.now();
  const statements = [];
  const applied = [];
  const conflicts = [];

  changes.forEach((change, index) => {
    const seq = firstSeq + index;
    const prior = existing.get(`${change.collection}\u0000${change.key}`);
    const baseSeq = Number(change.baseSeq || 0);
    // 有人在我上次见到它之后改过 → 记冲突台账并跳过，绝不静默覆盖。
    if (prior && Number(prior.server_seq) > baseSeq) {
      conflicts.push({
        collection: change.collection,
        key: change.key,
        baseSeq,
        seq: Number(prior.server_seq),
        payload: prior.payload,
        updatedBy: prior.updated_by_user,
      });
      statements.push(
        env.DB.prepare(
          "INSERT INTO conflicts(collection, record_key, base_seq, server_seq, by_device, at) " +
            "VALUES(?,?,?,?,?,?)",
        ).bind(change.collection, change.key, baseSeq, Number(prior.server_seq), session.device_id, now),
      );
      return;
    }
    statements.push(
      env.DB.prepare(
        "INSERT INTO records(collection, record_key, payload, server_seq, updated_by_user, updated_by_dev, updated_at, deleted) " +
          "VALUES(?,?,?,?,?,?,?,?) " +
          "ON CONFLICT(collection, record_key) DO UPDATE SET " +
          "payload=excluded.payload, server_seq=excluded.server_seq, updated_by_user=excluded.updated_by_user, " +
          "updated_by_dev=excluded.updated_by_dev, updated_at=excluded.updated_at, deleted=excluded.deleted",
      ).bind(
        change.collection,
        change.key,
        change.payload,
        seq,
        session.user_name,
        session.device_id,
        now,
        change.deleted ? 1 : 0,
      ),
    );
    applied.push({ collection: change.collection, key: change.key, seq });
  });

  if (statements.length) await env.DB.batch(statements);

  await env.DB.prepare(
    "INSERT INTO devices(device_id, user_id, name, last_seen_at, last_pull_seq) VALUES(?,?,?,?,0) " +
      "ON CONFLICT(device_id) DO UPDATE SET user_id=excluded.user_id, last_seen_at=excluded.last_seen_at",
  )
    .bind(session.device_id, session.user_id, session.device_name, now)
    .run();

  return json({
    applied,
    conflicts,
    lastSeq: firstSeq + count - 1,
    // 请求批次已满说明还可能有余量，让客户端继续推（同时避开 Workers CPU 上限）。
    hasMore: changes.length === PUSH_MAX_CHANGES,
  });
}

async function handlePull(request, env, session) {
  const params = new URL(request.url).searchParams;
  const since = Math.max(0, Number(params.get("since") || 0));
  const limit = Math.min(
    Math.max(1, Number(params.get("limit") || PULL_MAX_LIMIT)),
    PULL_MAX_LIMIT,
  );

  // 排除「本设备自己写的」：同一账号的另一台设备仍会收到，不能被用户身份误排。
  const rows = await env.DB.prepare(
    "SELECT collection, record_key, payload, server_seq, deleted, updated_by_user, updated_at " +
      "FROM records WHERE server_seq > ? AND updated_by_dev <> ? ORDER BY server_seq ASC LIMIT ?",
  )
    .bind(since, session.device_id, limit)
    .all();
  const results = rows.results || [];
  const lastSeq = results.length ? Number(results[results.length - 1].server_seq) : since;

  let hasMore = false;
  if (results.length === limit) {
    const more = await env.DB.prepare(
      "SELECT 1 AS x FROM records WHERE server_seq > ? AND updated_by_dev <> ? LIMIT 1",
    )
      .bind(lastSeq, session.device_id)
      .first();
    hasMore = !!more;
  }

  await env.DB.prepare(
    "INSERT INTO devices(device_id, user_id, name, last_seen_at, last_pull_seq) VALUES(?,?,?,?,?) " +
      "ON CONFLICT(device_id) DO UPDATE SET last_seen_at=excluded.last_seen_at, last_pull_seq=excluded.last_pull_seq",
  )
    .bind(session.device_id, session.user_id, session.device_name, Date.now(), lastSeq)
    .run();

  return json({
    changes: results.map((row) => ({
      collection: row.collection,
      key: row.record_key,
      payload: row.payload,
      seq: Number(row.server_seq),
      deleted: !!row.deleted,
      updatedBy: row.updated_by_user,
      updatedAt: Number(row.updated_at),
    })),
    lastSeq,
    hasMore,
  });
}

async function handleStats(env) {
  const counts = await env.DB.prepare(
    "SELECT collection, " +
      "SUM(CASE WHEN deleted = 0 THEN 1 ELSE 0 END) AS live, " +
      "SUM(CASE WHEN deleted = 1 THEN 1 ELSE 0 END) AS deleted, " +
      "COUNT(*) AS total, MAX(server_seq) AS lastSeq " +
      "FROM records GROUP BY collection ORDER BY collection",
  ).all();
  const devices = await env.DB.prepare(
    "SELECT device_id, user_id, name, last_seen_at, last_pull_seq FROM devices " +
      "ORDER BY last_seen_at DESC",
  ).all();
  const conflictCount = await env.DB.prepare("SELECT COUNT(*) AS n FROM conflicts").first();
  const seq = await env.DB.prepare("SELECT v FROM meta WHERE k = 'seq'").first();
  return json({
    seq: seq ? Number(seq.v) : 0,
    collections: counts.results || [],
    devices: devices.results || [],
    conflictCount: conflictCount ? Number(conflictCount.n) : 0,
  });
}

/* --------------------------------------------------------------- 对象存储网关 */

async function handleBlobUrl(request, env) {
  const body = await request.json().catch(() => null);
  if (!body || !body.key) return fail("缺少 key");
  const op = body.op === "put" ? "put" : "get";
  const method = op === "put" ? "PUT" : "GET";
  const ttl = Math.min(Math.max(30, Number(body.ttlSeconds || PRESIGN_DEFAULT_TTL)), PRESIGN_MAX_TTL);
  const signed = await presignUrl(env, String(body.key), method, ttl);
  return json({ url: signed.url, expiresAt: signed.expiresAt, method });
}

async function handleBlobProbe(request, env) {
  const body = await request.json().catch(() => null);
  if (!body || !body.key) return fail("缺少 key");
  const response = await signedS3Fetch(env, { method: "HEAD", key: String(body.key) });
  if (response.status === 404) return json({ exists: false, status: 404 });
  if (response.status >= 200 && response.status < 300) {
    return json({
      exists: true,
      status: response.status,
      size: Number(response.headers.get("content-length") || 0),
    });
  }
  return json({ exists: false, status: response.status, message: await awaitingText(response) });
}

async function handleBlobDelete(request, env) {
  const body = await request.json().catch(() => null);
  if (!body || !body.key) return fail("缺少 key");
  const response = await signedS3Fetch(env, { method: "DELETE", key: String(body.key) });
  // S3 对不存在的键同样返回成功：删除意图已达成即算成功。
  if (response.status >= 200 && response.status < 300) return json({ ok: true });
  return json({ ok: false, status: response.status, message: await response.text() }, 502);
}

async function handleBlobList(request, env) {
  const params = new URL(request.url).searchParams;
  const prefix = params.get("prefix") || "";
  const query = { "list-type": "2" };
  if (prefix) query.prefix = prefix;
  const token = params.get("continuation-token");
  if (token) query["continuation-token"] = token;

  const response = await signedS3Fetch(env, { method: "GET", query });
  if (response.status < 200 || response.status >= 300) {
    return json({ error: `列举失败（${response.status}）`, body: await response.text() }, 502);
  }
  // 直接回原始 XML：客户端已有的 parseListObjectsXml 是带单测的实现，不重写第二份。
  return new Response(await response.text(), {
    headers: { "content-type": "application/xml; charset=utf-8" },
  });
}

async function handleBlobSelfCheck(env) {
  if (!env.S3_ENDPOINT || !env.S3_BUCKET || !env.S3_ACCESS_KEY || !env.S3_SECRET_KEY) {
    return json({ ok: false, message: "服务端未配置对象存储" });
  }
  const response = await signedS3Fetch(env, { method: "HEAD" });
  if (response.status >= 200 && response.status < 300) {
    return json({ ok: true, status: response.status });
  }
  return json({ ok: false, status: response.status, message: await response.text() });
}

async function awaitingText(response) {
  try {
    return await response.text();
  } catch (e) {
    return "";
  }
}

/* ------------------------------------------------------------------- MCP 服务端 */

/**
 * 极简 MCP（Streamable HTTP，JSON-RPC）。读写、禁删。
 * 刻意不提供 create/delete 工具：新建记录需要客户端 Snapshot 的完整字段模板，
 * 让 AI 凭空拼一份可能产生客户端导入不了的载荷；整理既有数据用手上的记录本身当模板最稳。
 */
const MCP_TOOLS = [
  {
    name: "sync_status",
    description: "查看同步中枢的整体状态：序号水位、各集合记录数（在用/已删）、各设备最后同步到的序号、未决冲突数。",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
  },
  {
    name: "list_records",
    description: "按集合列出记录。collection 取值如 items（家当）、reminders（待办提醒）、ledger_records（记账）、categories（分类）、locations（存放地）。",
    inputSchema: {
      type: "object",
      properties: {
        collection: { type: "string" },
        limit: { type: "integer", minimum: 1, maximum: 200 },
        includeDeleted: { type: "boolean" },
      },
      required: ["collection"],
      additionalProperties: false,
    },
  },
  {
    name: "get_record",
    description: "取单条记录的完整载荷（JSON 字符串），改之前先读它。",
    inputSchema: {
      type: "object",
      properties: { collection: { type: "string" }, key: { type: "string" } },
      required: ["collection", "key"],
      additionalProperties: false,
    },
  },
  {
    name: "update_record",
    description:
      "改单条记录：把 fields 里的字段合并进原载荷后写回（只覆盖给出的字段，其余保持不动）。" +
      "改之前必须先用 get_record 读一遍，字段名要沿用原载荷里的写法。不允许删除记录。",
    inputSchema: {
      type: "object",
      properties: {
        collection: { type: "string" },
        key: { type: "string" },
        fields: { type: "object" },
      },
      required: ["collection", "key", "fields"],
      additionalProperties: false,
    },
  },
];

async function handleMcp(request, env) {
  const provided = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "").trim();
  if (!env.MCP_TOKEN || !equalConstantTime(provided, env.MCP_TOKEN)) {
    return new Response(JSON.stringify({ error: "unauthorized" }), {
      status: 401,
      headers: { "content-type": "application/json", "www-authenticate": "Bearer" },
    });
  }
  if (request.method === "GET") {
    // 不支持服务端主动推送（SSE），如实拒绝而不是假装成功。
    return fail("本服务端不提供 SSE 流", 405);
  }
  const message = await request.json().catch(() => null);
  if (!message || typeof message.method !== "string") {
    return json({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } }, 400);
  }
  const id = message.id ?? null;
  const reply = (result) => json({ jsonrpc: "2.0", id, result });
  const error = (code, msg) => json({ jsonrpc: "2.0", id, error: { code, message: msg } });

  if (message.method === "initialize") {
    return reply({
      protocolVersion: message.params?.protocolVersion || "2025-06-18",
      capabilities: { tools: {} },
      serverInfo: { name: "lemonbox-sync", version: VERSION },
    });
  }
  if (message.method === "notifications/initialized" || message.method === "notifications/cancelled") {
    return new Response(null, { status: 202 });
  }
  if (message.method === "ping") return reply({});
  if (message.method === "tools/list") return reply({ tools: MCP_TOOLS });
  if (message.method !== "tools/call") return error(-32601, `未知方法：${message.method}`);

  const name = message.params?.name;
  const args = message.params?.arguments || {};
  try {
    const payload = await mcpCall(env, name, args);
    return reply({ content: [{ type: "text", text: JSON.stringify(payload, null, 2) }] });
  } catch (e) {
    return reply({
      content: [{ type: "text", text: `调用失败：${e && e.message ? e.message : e}` }],
      isError: true,
    });
  }
}

async function mcpCall(env, name, args) {
  if (name === "sync_status") {
    const response = await handleStats(env);
    return response.json();
  }
  if (name === "list_records") {
    const collection = String(args.collection || "").trim();
    if (!collection) throw new Error("缺少 collection");
    const limit = Math.min(Math.max(1, Number(args.limit || 50)), 200);
    const sql =
      "SELECT record_key, payload, server_seq, deleted, updated_by_user, updated_at FROM records " +
      `WHERE collection = ?${args.includeDeleted ? "" : " AND deleted = 0"} ` +
      "ORDER BY server_seq DESC LIMIT ?";
    const rows = await env.DB.prepare(sql).bind(collection, limit).all();
    return (rows.results || []).map((row) => ({
      key: row.record_key,
      seq: Number(row.server_seq),
      deleted: !!row.deleted,
      updatedBy: row.updated_by_user,
      updatedAt: Number(row.updated_at),
      payload: safeParse(row.payload),
    }));
  }
  if (name === "get_record") {
    const collection = String(args.collection || "").trim();
    const key = String(args.key || "").trim();
    if (!collection || !key) throw new Error("缺少 collection 或 key");
    const row = await env.DB.prepare(
      "SELECT record_key, payload, server_seq, deleted, updated_by_user FROM records " +
        "WHERE collection = ? AND record_key = ?",
    )
      .bind(collection, key)
      .first();
    if (!row) throw new Error("记录不存在（key 要用载荷里的 syncId，不是本机自增 id）");
    return {
      key: row.record_key,
      seq: Number(row.server_seq),
      deleted: !!row.deleted,
      updatedBy: row.updated_by_user,
      payload: safeParse(row.payload),
    };
  }
  if (name === "update_record") {
    const collection = String(args.collection || "").trim();
    const key = String(args.key || "").trim();
    const fields = args.fields;
    if (!collection || !key) throw new Error("缺少 collection 或 key");
    if (!fields || typeof fields !== "object" || Array.isArray(fields)) {
      throw new Error("fields 需要是一个字段对象");
    }
    const row = await env.DB.prepare(
      "SELECT payload, server_seq FROM records WHERE collection = ? AND record_key = ?",
    )
      .bind(collection, key)
      .first();
    if (!row) throw new Error("记录不存在：不允许凭空创建，先用 get_record 确认 key");
    const origin = safeParse(row.payload);
    if (!origin || typeof origin !== "object") throw new Error("原载荷不是合法 JSON，拒绝改写");
    const merged = { ...origin, ...fields };
    // 只覆盖给出的字段；载荷里的 syncId 不允许被改（改了就跨设备指错行）。
    if (merged.syncId !== origin.syncId) throw new Error("不允许修改 syncId");

    const seqRow = await env.DB.prepare("UPDATE meta SET v = v + 1 WHERE k = 'seq' RETURNING v")
      .first();
    const seq = Number(seqRow.v);
    await env.DB.prepare(
      "UPDATE records SET payload = ?, server_seq = ?, updated_by_user = ?, updated_by_dev = ?, " +
        "updated_at = ?, deleted = 0 WHERE collection = ? AND record_key = ?",
    )
      .bind(JSON.stringify(merged), seq, "AI助手", MCP_DEVICE_ID, Date.now(), collection, key)
      .run();
    return { ok: true, key, seq, appliedFields: Object.keys(fields), updatedBy: "AI助手" };
  }
  throw new Error(`未知工具：${name}`);
}

const MCP_DEVICE_ID = "mcp";

function safeParse(text) {
  try {
    return JSON.parse(text);
  } catch (e) {
    return null;
  }
}

/* ---------------------------------------------------------------------- 路由 */

export default {
  async fetch(request, env) {
    const { pathname } = new URL(request.url);
    try {
      if (pathname === "/health") return json({ ok: true, version: VERSION });
      if (pathname === "/v1/login" && request.method === "POST") return await handleLogin(request, env);
      if (pathname === "/v1/admin/users" && request.method === "POST") return await handleAdminUsers(request, env);
      if (pathname.startsWith("/mcp")) return await handleMcp(request, env);

      const session = await requireSession(request, env);
      if (session instanceof Response) return session;

      if (pathname === "/v1/logout" && request.method === "POST") return await handleLogout(request, env, session);
      if (pathname === "/v1/push" && request.method === "POST") return await handlePush(request, env, session);
      if (pathname === "/v1/pull" && request.method === "GET") return await handlePull(request, env, session);
      if (pathname === "/v1/stats" && request.method === "GET") return await handleStats(env);

      if (pathname === "/v1/blob/url" && request.method === "POST") return await handleBlobUrl(request, env);
      if (pathname === "/v1/blob/probe" && request.method === "POST") return await handleBlobProbe(request, env);
      if (pathname === "/v1/blob/delete" && request.method === "POST") return await handleBlobDelete(request, env);
      if (pathname === "/v1/blob/list" && request.method === "GET") return await handleBlobList(request, env);
      if (pathname === "/v1/blob/selfcheck" && request.method === "GET") return await handleBlobSelfCheck(env);

      return fail("未知接口", 404);
    } catch (e) {
      return fail(`服务端异常：${e && e.message ? e.message : e}`, 500);
    }
  },
};
