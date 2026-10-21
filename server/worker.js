/**
 * 柠檬百宝箱 · 同步中枢（Cloudflare Worker 单文件实现）
 *
 * 三个职责，同一份部署：
 *   1. /v1/*        —— 登录 + D1 权威记录同步：/v1/meta 轻量探测、/v1/snapshot 按集合快照、
 *                       /v1/pull（别名 /v1/changes）增量；内容未变的写入不消耗序号
 *   2. /v1/blob/*   —— 自建 S3 兼容存储（Filebase）的密钥只在这里；客户端每次只拿几分钟有效的预签名 URL
 *   3. /mcp         —— MCP 服务端，读写、禁删，供 AI 整理家庭数据
 *
 * 刻意保持单文件 ES module、无需打包：仓库内 wrangler.jsonc 支持
 * `npx wrangler deploy` 部署与 `npx wrangler dev` 本地预览。
 * 刻意不做的事：不下发长期密钥、不提供删除授权、不用墙钟判胜负。
 *
 * secrets（只存在于 Worker，绝不进仓库与 APK）：
 *   S3_ENDPOINT / S3_REGION / S3_BUCKET / S3_PREFIX / S3_ACCESS_KEY / S3_SECRET_KEY
 *   ADMIN_TOKEN / MCP_TOKEN
 * 绑定：DB（D1）
 */

const VERSION = "1.0.0";
/**
 * Cloudflare Workers 免费版单次请求 CPU 约 10ms；PBKDF2 100k 次在本机约 38ms，
 * 线上很容易触发 1102（Exceeded CPU Limit）。这里降到 10k（约 3~4ms），
 * 配合登录失败限流与 90 天会话；旧账号首次登录成功后会按新迭代数自动重哈希，
 * 之后不再走 100k。需要更强抗暴力破解时，升级 Workers Paid 后把该值调回 100000。
 */
const PBKDF2_ITERATIONS = 10000;
const SESSION_TTL_MS = 90 * 24 * 60 * 60 * 1000;
/** 单次推送/拉取批量刻意收敛：减少单请求的 CPU、内存与 D1 行数。 */
const PUSH_MAX_CHANGES = 100;
const PULL_MAX_LIMIT = 200;
/** 快照接口单页上限：快照只读、不写 devices，可以比增量页大一些。 */
const SNAPSHOT_MAX_LIMIT = 500;
/**
 * 快照单页载荷的字符数上限（近似字节）。
 *
 * 免费档单请求 CPU 约 10ms；500 条小 payload 没问题，但遇到大 payload 时
 * JSON 序列化会顶到上限。超过就少返回几条，让客户端按 nextCursor 继续，
 * 不把整个 500 条硬塞进一次响应。
 */
const SNAPSHOT_MAX_BYTES = 256 * 1024;
/** push 请求体与单条载荷上限：防止一次请求把 D1/Worker 内存打满。 */
const PUSH_MAX_BODY_BYTES = 2 * 1024 * 1024;
const PUSH_MAX_RECORD_CHARS = 64 * 1024;
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

/**
 * 登录失败限流：按「IP + 账号」两个桶计数，窗口内失败过多就短暂锁定。
 *
 * 无状态 Worker 不能把计数放内存，故落在 D1；表缺失或 D1 抖动时一律**放行**，
 * 宁可暂时不限流，也不能因为限流表没建好把所有人挡在登录页外。
 */
const LOGIN_WINDOW_MS = 5 * 60 * 1000;
const LOGIN_MAX_FAILURES = 10;
const LOGIN_BLOCK_MS = 5 * 60 * 1000;

function clientIp(request) {
  const raw = request.headers.get("CF-Connecting-IP") ||
    request.headers.get("X-Forwarded-For") ||
    "local";
  return String(raw).split(",")[0].trim() || "local";
}

function loginBuckets(request, username) {
  return [`ip:${clientIp(request)}`, `user:${username.toLowerCase()}`];
}

async function loginBlockedSeconds(env, buckets) {
  const now = Date.now();
  for (const bucket of buckets) {
    const row = await env.DB.prepare(
      "SELECT blocked_until FROM login_failures WHERE bucket = ?",
    )
      .bind(bucket)
      .first();
    if (row && Number(row.blocked_until) > now) {
      return Math.ceil((Number(row.blocked_until) - now) / 1000);
    }
  }
  return 0;
}

async function safeLoginBlockedSeconds(env, buckets) {
  try {
    return await loginBlockedSeconds(env, buckets);
  } catch (e) {
    return 0;
  }
}

async function recordLoginFailure(env, buckets) {
  const now = Date.now();
  for (const bucket of buckets) {
    const row = await env.DB.prepare(
      "SELECT failures, first_at, last_at FROM login_failures WHERE bucket = ?",
    )
      .bind(bucket)
      .first();
    const withinWindow = row && Number(row.last_at) >= now - LOGIN_WINDOW_MS;
    const failures = withinWindow ? Number(row.failures) + 1 : 1;
    const firstAt = withinWindow ? Number(row.first_at) : now;
    const blockedUntil = failures >= LOGIN_MAX_FAILURES ? now + LOGIN_BLOCK_MS : 0;
    await env.DB.prepare(
      "INSERT INTO login_failures(bucket, failures, first_at, last_at, blocked_until) " +
        "VALUES(?,?,?,?,?) " +
        "ON CONFLICT(bucket) DO UPDATE SET failures=excluded.failures, " +
        "first_at=excluded.first_at, last_at=excluded.last_at, blocked_until=excluded.blocked_until",
    )
      .bind(bucket, failures, firstAt, now, blockedUntil)
      .run();
  }
  // 顺手清掉一天前的记录，避免桶无限增长。
  await env.DB.prepare("DELETE FROM login_failures WHERE last_at < ?")
    .bind(now - 24 * 60 * 60 * 1000)
    .run();
}

async function safeRecordLoginFailure(env, buckets) {
  try {
    await recordLoginFailure(env, buckets);
  } catch (e) {
    /* 限流表未建好时不影响登录本身 */
  }
}

async function safeClearLoginFailures(env, buckets) {
  try {
    await env.DB.batch(
      buckets.map((bucket) =>
        env.DB.prepare("DELETE FROM login_failures WHERE bucket = ?").bind(bucket)
      ),
    );
  } catch (e) {
    /* 同上 */
  }
}

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

  const buckets = loginBuckets(request, username);
  const retryAfter = await safeLoginBlockedSeconds(env, buckets);
  if (retryAfter > 0) {
    return new Response(
      JSON.stringify({ error: `登录失败次数过多，请 ${retryAfter} 秒后再试` }),
      {
        status: 429,
        headers: {
          "content-type": "application/json; charset=utf-8",
          "retry-after": String(retryAfter),
        },
      },
    );
  }

  const user = await env.DB.prepare("SELECT * FROM users WHERE name = ?").bind(username).first();
  // 账号不存在与密码错误给同一句话，避免枚举出有哪些账号。
  if (!user) {
    await safeRecordLoginFailure(env, buckets);
    return fail("账号或密码不正确", 401);
  }
  const hash = await pbkdf2Hex(String(body.password), user.salt, user.iterations);
  if (!equalConstantTime(hash, user.hash)) {
    await safeRecordLoginFailure(env, buckets);
    return fail("账号或密码不正确", 401);
  }
  await safeClearLoginFailures(env, buckets);

  // 旧账号的 iterations 可能仍是 100000；首次登录成功后按新迭代数重哈希，
  // 避免之后每次登录都跑一次高 CPU 的 PBKDF2。失败不影响本次登录。
  if (Number(user.iterations) > PBKDF2_ITERATIONS) {
    try {
      const newSalt = randomToken().slice(0, 32);
      const newHash = await pbkdf2Hex(String(body.password), newSalt, PBKDF2_ITERATIONS);
      await env.DB.prepare(
        "UPDATE users SET salt = ?, iterations = ?, hash = ? WHERE id = ?",
      )
        .bind(newSalt, PBKDF2_ITERATIONS, newHash, user.id)
        .run();
    } catch (e) {
      /* 重哈希失败不影响本次登录，下次再试 */
    }
  }

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
  const contentLength = Number(request.headers.get("content-length") || 0);
  if (contentLength > PUSH_MAX_BODY_BYTES) {
    return fail("请求体过大，请分批推送", 413);
  }
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
    if (change.payload.length > PUSH_MAX_RECORD_CHARS) {
      return fail(`单条记录载荷过大（上限 ${PUSH_MAX_RECORD_CHARS} 字符）`, 413);
    }
  }

  // 1) 先读这些记录当前的服务端序号，判断是否有人在我之后改过。
  //    单条 SQL 绑定参数 ≤100，一条记录 2 个参数 → 每批 50 条。
  const existing = new Map();
  for (const part of chunk(changes, 50)) {
    const placeholders = part.map(() => "(?,?)").join(",");
    const binds = part.flatMap((c) => [c.collection, c.key]);
    const rows = await env.DB.prepare(
      "SELECT collection, record_key, server_seq, payload, deleted, updated_by_user FROM records " +
        `WHERE (collection, record_key) IN (${placeholders})`,
    )
      .bind(...binds)
      .all();
    for (const row of rows.results || []) {
      existing.set(`${row.collection}\u0000${row.record_key}`, row);
    }
  }

  const now = Date.now();
  const statements = [];
  const applied = [];
  const conflicts = [];
  /** 真正需要写库的变更；只有它们消耗 server_seq。 */
  const toWrite = [];

  // 2) 逐条判定。顺序很重要：先判「内容没变」，再判冲突，最后才进入待写队列。
  //    内容与墓碑都相同 → 服务端已经是这一版，直接把原序号回给客户端清脏，
  //    不发号、不写库。否则「没有数据变化」的空同步每次都会把序号 +1，
  //    并把同一行重新标成新版本，让所有设备的水线永远追着它跑。
  changes.forEach((change) => {
    const prior = existing.get(`${change.collection}\u0000${change.key}`);
    const baseSeq = Number(change.baseSeq || 0);

    if (prior && prior.payload === change.payload && !!prior.deleted === !!change.deleted) {
      applied.push({
        collection: change.collection,
        key: change.key,
        seq: Number(prior.server_seq),
        // 明确告诉客户端「这次没有真的写入」：序号原样返回，只用来对齐水位/清脏。
        noop: true,
      });
      return;
    }

    // 有人在我上次见到它之后改过 → 记冲突台账并跳过，绝不静默覆盖。
    // force=1 只来自用户在诊断页主动执行的「以本机为准合并上传」：那是明确的本地优先动作，
    // 跳过这个检查，把本机版本直接写到服务端（之后各设备按各自水线拉到它）。
    if (!change.force && prior && Number(prior.server_seq) > baseSeq) {
      conflicts.push({
        collection: change.collection,
        key: change.key,
        baseSeq,
        seq: Number(prior.server_seq),
        // 把服务端那一版连载荷一起带回：客户端据此把服务端版本落地到本机，
        // 不再只是「序号对齐、内容分叉」。载体会跟着响应走一遍网络，冲突本来就是小批量，可接受。
        payload: prior.payload,
        deleted: !!prior.deleted,
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
    toWrite.push(change);
  });

  // 3) 只给真正要写的变更领号（RETURNING 拿回发完后的值，倒推起始号）。
  //    冲突与幂等条目不再占号：序号空洞只可能来自并发/失败，不再来自每次空同步。
  let lastSeq = 0;
  if (toWrite.length) {
    const meta = await env.DB.prepare("UPDATE meta SET v = v + ? WHERE k = 'seq' RETURNING v")
      .bind(toWrite.length)
      .first();
    const firstSeq = (meta && Number(meta.v) ? Number(meta.v) : 0) - toWrite.length + 1;
    lastSeq = firstSeq + toWrite.length - 1;
    toWrite.forEach((change, index) => {
      const seq = firstSeq + index;
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
  }

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
    lastSeq,
    // 请求批次已满说明还可能有余量，让客户端继续推（同时避开 Workers CPU 上限）。
    hasMore: changes.length === PUSH_MAX_CHANGES,
  });
}

async function handlePull(request, env, session) {
  const params = new URL(request.url).searchParams;
  const sinceRaw = Number(params.get("since") || 0);
  let since = Number.isFinite(sinceRaw) ? Math.max(0, Math.floor(sinceRaw)) : 0;
  const limitRaw = Number(params.get("limit") || PULL_MAX_LIMIT);
  const limit = Number.isFinite(limitRaw)
    ? Math.min(Math.max(1, Math.floor(limitRaw)), PULL_MAX_LIMIT)
    : PULL_MAX_LIMIT;

  // 服务端身份与当前序号：水位比它还大只有一种可能——库被删掉/重建过。
  // 旧客户端的 since 还停在上一个库的序号上，不钳制就会永远拉空；
  // 钳到 0 后它会把当前全量当成增量拉走，至少不会丢数据。
  const metaRow = await env.DB.prepare("SELECT v FROM meta WHERE k = 'seq'").first();
  const currentSeq = metaRow ? Number(metaRow.v) || 0 : 0;
  let resetRequired = false;
  if (since > currentSeq) {
    since = 0;
    resetRequired = true;
  }

  // 排除「本设备自己写的」：同一账号的另一台设备仍会收到，不能被用户身份误排。
  // 例外：includeSelf=1（诊断页的「以服务端为准重建」）不排除自己——重建后本机是空的，
  // 服务端上很多记录本来就是这台设备推上去的，排除自己就什么也拉不回来。
  //
  // 关键：**分页与水线必须按「扫描位置」计算，而不是按过滤后的结果**。
  // 早先 SQL 里直接排除本设备、再用过滤后的最后一条当 lastSeq，会有一个致命后果：
  // 一页 200 条恰好全是本设备刚推上去的记录时，过滤后为空，lastSeq 原地不动，
  // 客户端又因「本页没有变更」直接停止翻页——水位永远卡在这批记录之前，
  // 其他设备之后写的任何新数据都再也拉不到。现在先按 server_seq 顺序分页扫描，
  // 返回 scannedSeq（含被过滤行）供客户端推进水线；过滤只影响返回给客户端的 changes。
  const includeSelf = params.get("includeSelf") === "1";
  const rows = await env.DB.prepare(
    "SELECT collection, record_key, payload, server_seq, deleted, updated_by_user, updated_at, updated_by_dev " +
      "FROM records WHERE server_seq > ? ORDER BY server_seq ASC LIMIT ?",
  )
    .bind(since, limit)
    .all();
  const scanned = rows.results || [];
  const scannedSeq = scanned.length ? Number(scanned[scanned.length - 1].server_seq) : since;
  const visible = includeSelf
    ? scanned
    : scanned.filter((row) => row.updated_by_dev !== session.device_id);

  // 精确判断：本页取满且扫描位置还没到当前最大序号，才真的还有下一页。
  // 旧实现只看「取满」，数据量正好是 200 的倍数时必然多一次空请求；
  // 现在多一次很小的 meta 主键读，换掉一整轮 HTTP + D1 往返，整体更快。
  const hasMore = scanned.length === limit && scannedSeq < currentSeq;

  // 诊断用的 last_pull_seq 只在整轮拉取结束时写一次。每页一次 UPSERT 意味着
  // 每个请求固定一次 D1 写，页数多时它比读本身还慢。
  if (!hasMore) {
    await env.DB.prepare(
      "INSERT INTO devices(device_id, user_id, name, last_seen_at, last_pull_seq) VALUES(?,?,?,?,?) " +
        "ON CONFLICT(device_id) DO UPDATE SET last_seen_at=excluded.last_seen_at, last_pull_seq=excluded.last_pull_seq",
    )
      .bind(session.device_id, session.user_id, session.device_name, Date.now(), scannedSeq)
      .run();
  }

  return json({
    changes: visible.map((row) => ({
      collection: row.collection,
      key: row.record_key,
      payload: row.payload,
      seq: Number(row.server_seq),
      deleted: !!row.deleted,
      updatedBy: row.updated_by_user,
      updatedAt: Number(row.updated_at),
    })),
    // lastSeq 保持旧字段名，语义改为「本次扫描到的位置」；scannedSeq 是新名字，
    // 新客户端优先读它，旧客户端读 lastSeq 也能正确前进。
    lastSeq: scannedSeq,
    scannedSeq,
    hasMore,
    /** `since` 比服务端当前序号还大，说明库被重建过；客户端应清水位/缓存重新快照。 */
    resetRequired,
    /** 当前全局序号，客户端可用来判断“是否真的还有变化”。 */
    seq: currentSeq,
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

/**
 * 轻量同步元信息：客户端每次打开 / 回前台先看这里，序号没变就一个字节的业务数据都不用拉。
 *
 * 默认**只读 `meta` 与 `sync_meta` 两个主键**，不碰 `records`——这是每次 focus /
 * 同步都会调用的热路径，不能在这里做全表 GROUP BY。只有首次/重建、客户端确实
 * 需要按集合快照时，才传 `?collections=1` 做一次集合统计。
 */
async function handleMeta(request, env) {
  const seqRow = await env.DB.prepare("SELECT v FROM meta WHERE k = 'seq'").first();
  let instanceId = "";
  try {
    const row = await env.DB.prepare("SELECT v FROM sync_meta WHERE k = 'instance_id'").first();
    instanceId = row ? String(row.v) : "";
  } catch (e) {
    // 旧库还没执行新 schema：没有 sync_meta 表时按空实例处理，不影响其他接口。
  }
  let collections = [];
  if (new URL(request.url).searchParams.get("collections") === "1") {
    const cols = await env.DB.prepare(
      "SELECT collection, SUM(CASE WHEN deleted = 0 THEN 1 ELSE 0 END) AS live, " +
        "COUNT(*) AS total, MAX(server_seq) AS version " +
        "FROM records GROUP BY collection ORDER BY collection",
    ).all();
    collections = (cols.results || []).map((row) => ({
      collection: row.collection,
      count: Number(row.live || 0),
      total: Number(row.total || 0),
      version: Number(row.version || 0),
    }));
  }
  return json({
    instanceId,
    seq: seqRow ? Number(seqRow.v) : 0,
    collections,
  });
}

/**
 * 按集合返回「当前活数据」的快照，供首次加载 / 重建使用。
 *
 * 不返回墓碑：空客户端没有旧记录需要删除；墓碑只通过增量 `/v1/pull` 传播。
 * `upTo` 把快照钉在某个全局序号上，保证读到的是一个一致点；客户端随后从
 * `upTo` 拉增量，补齐快照期间其他设备写入的变化。
 */
async function handleSnapshot(request, env) {
  const params = new URL(request.url).searchParams;
  const collection = String(params.get("collection") || "").trim();
  if (!collection) return fail("缺少 collection");
  if (collection.length > 64) return fail("collection 过长");
  const cursorRaw = Number(params.get("cursor") || 0);
  const cursor = Number.isFinite(cursorRaw) ? Math.max(0, Math.floor(cursorRaw)) : 0;
  const upToRaw = Number(params.get("upTo") || 0);
  const upTo = Number.isFinite(upToRaw) && upToRaw > 0 ? Math.floor(upToRaw) : 0;
  const limitRaw = Number(params.get("limit") || SNAPSHOT_MAX_LIMIT);
  const limit = Number.isFinite(limitRaw)
    ? Math.min(Math.max(1, Math.floor(limitRaw)), SNAPSHOT_MAX_LIMIT)
    : SNAPSHOT_MAX_LIMIT;

  const where = ["collection = ?", "deleted = 0", "server_seq > ?"];
  const binds = [collection, cursor];
  if (upTo) {
    where.push("server_seq <= ?");
    binds.push(upTo);
  }
  binds.push(limit);
  const rows = await env.DB.prepare(
    "SELECT collection, record_key, payload, server_seq, deleted, updated_by_user, updated_at " +
      `FROM records WHERE ${where.join(" AND ")} ORDER BY server_seq ASC LIMIT ?`,
  )
    .bind(...binds)
    .all();
  const list = rows.results || [];
  // 按 payload 字符数截断单页，避免大载荷把一次响应撑到免费档 CPU/内存上限。
  // 至少保留一条，保证 nextCursor 一定前进（单条超大记录也不会卡死）。
  let page = list;
  let payloadChars = 0;
  for (let i = 0; i < list.length; i++) {
    payloadChars += String(list[i].payload || "").length;
    if (i > 0 && payloadChars > SNAPSHOT_MAX_BYTES) {
      page = list.slice(0, i);
      break;
    }
  }
  const truncatedByBytes = page.length < list.length;
  const nextCursor = page.length ? Number(page[page.length - 1].server_seq) : cursor;
  // 取满一页时精确探一下后面还有没有行，避免客户端为“正好取满”多拉一次空页。
  // 快照路径不写 devices，这次索引探测比一整轮 HTTP + D1 往返便宜得多。
  let done = !truncatedByBytes && page.length < limit;
  if (!done && !truncatedByBytes) {
    const probeWhere = ["collection = ?", "deleted = 0", "server_seq > ?"];
    const probeBinds = [collection, nextCursor];
    if (upTo) {
      probeWhere.push("server_seq <= ?");
      probeBinds.push(upTo);
    }
    const probe = await env.DB.prepare(
      `SELECT 1 AS n FROM records WHERE ${probeWhere.join(" AND ")} LIMIT 1`,
    )
      .bind(...probeBinds)
      .first();
    done = !probe;
  }
  return json({
    collection,
    upTo,
    changes: page.map((row) => ({
      collection: row.collection,
      key: row.record_key,
      payload: row.payload,
      seq: Number(row.server_seq),
      deleted: false,
      updatedBy: row.updated_by_user,
      updatedAt: Number(row.updated_at),
    })),
    nextCursor,
    done,
  });
}

/* --------------------------------------------------------------- 对象存储网关 */

/**
 * 只允许图片对象键：`images/<内容哈希>.jpg`。
 * 共享桶场景下不能信任客户端给的任意 key，否则一个登录用户就能 probe/delete/list 到
 * 桶里与业务无关的对象。前缀之外的 key 一律拒绝。
 */
const BLOB_KEY_PATTERN = /^images\/[A-Za-z0-9][A-Za-z0-9._-]*$/;

function validBlobKey(key) {
  return typeof key === "string" && BLOB_KEY_PATTERN.test(key);
}

async function handleBlobUrl(request, env) {
  const body = await request.json().catch(() => null);
  if (!body || !body.key) return fail("缺少 key");
  if (!validBlobKey(body.key)) return fail("非法的对象键", 400);
  const op = body.op === "put" ? "put" : "get";
  const method = op === "put" ? "PUT" : "GET";
  const ttl = Math.min(Math.max(30, Number(body.ttlSeconds || PRESIGN_DEFAULT_TTL)), PRESIGN_MAX_TTL);
  const signed = await presignUrl(env, String(body.key), method, ttl);
  return json({ url: signed.url, expiresAt: signed.expiresAt, method });
}

async function handleBlobProbe(request, env) {
  const body = await request.json().catch(() => null);
  if (!body || !body.key) return fail("缺少 key");
  if (!validBlobKey(body.key)) return fail("非法的对象键", 400);
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
  if (!validBlobKey(body.key)) return fail("非法的对象键", 400);
  const response = await signedS3Fetch(env, { method: "DELETE", key: String(body.key) });
  // S3 对不存在的键同样返回成功：删除意图已达成即算成功。
  if (response.status >= 200 && response.status < 300) return json({ ok: true });
  return json({ ok: false, status: response.status, message: await response.text() }, 502);
}

async function handleBlobList(request, env) {
  const params = new URL(request.url).searchParams;
  const prefix = params.get("prefix") || "images/";
  // 空 prefix 也收敛到 images/：共享桶里不该让任意登录用户枚举整桶对象。
  if (prefix !== "images" && !prefix.startsWith("images/")) {
    return fail("只允许列举 images/ 前缀", 403);
  }
  const query = { "list-type": "2", prefix };
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
 * MCP 走浏览器或跨域客户端时，预检请求不带 Authorization；因此 CORS 头必须在
 * 鉴权之前返回，否则浏览器只会看到 401，永远发不出真正的 initialize。
 */
const MCP_PROTOCOL_VERSION = "2025-06-18";

const MCP_CORS_HEADERS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "POST, GET, OPTIONS, DELETE",
  "access-control-allow-headers":
    "authorization, content-type, mcp-session-id, mcp-protocol-version",
  "access-control-expose-headers": "mcp-session-id",
  "access-control-max-age": "86400",
};

function mcpJson(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", ...MCP_CORS_HEADERS },
  });
}

/** MCP 可管理的集合（与 App 的 SyncCollections 对齐）。 */
const MCP_COLLECTIONS = {
  items: "家当物品",
  categories: "家当分类",
  locations: "存放位置",
  ledger_records: "账目",
  ledger_categories: "记账分类",
  ledger_assets: "账户",
  ledger_budgets: "预算",
  ledger_templates: "记账模板",
  ledger_recurring_rules: "周期记账规则",
  anniversaries: "纪念日",
  reminders: "待办提醒",
  time_capsules: "时间胶囊",
  packing_trips: "打包行程",
  packing_items: "打包条目",
};

/**
 * `update_settings` 允许 AI 改写的偏好字段。
 *
 * 刻意不包含 recipes / weeklyMenu / cookedMenuDates：这些是结构化大对象，
 * 让 AI 整块覆盖会轻易毁掉用户菜谱库；需要管理菜谱时应使用记录级工具或 App。
 */
const MCP_SETTING_FIELDS = new Set([
  "nickname",
  "reminderLadder",
  "reminderTimes",
  "customReminderTimes",
  "mealPrepDayShift",
  "mealPrepFireTime",
  "budgetReminderEnabled",
  "ledgerMonthStartDay",
  "mealPlanDays",
  "togetherStartDate",
  "togetherLabelName",
  "customStatuses",
]);

/**
 * 极简 MCP（Streamable HTTP，JSON-RPC）。
 *
 * 家庭共享同一份数据（服务端不做 user_id 隔离），MCP_TOKEN 就是家庭的写入口：
 * 拿到令牌即可读写全部记录。工具覆盖「读、建、改、软删、设置」五类，
 * 写入一律走服务端序号，App 下一次增量拉取即可看到。
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
      "改之前必须先用 get_record 读一遍，字段名要沿用原载荷里的写法。" +
      "可传 expectedSeq（get_record 返回的 seq）做乐观锁；不一致会拒绝而不是覆盖别人的修改。",
    inputSchema: {
      type: "object",
      properties: {
        collection: { type: "string" },
        key: { type: "string" },
        fields: { type: "object" },
        expectedSeq: { type: "integer", description: "可选：读到时的 seq，用于防止覆盖并发修改" },
      },
      required: ["collection", "key", "fields"],
      additionalProperties: false,
    },
  },
  {
    name: "create_record",
    description:
      "新建一条记录。payload 只需给出该集合的业务字段（参考 list_records 返回的同类记录），" +
      "id/syncId/updatedAt 由服务端统一生成；deletedAt/purgedAt 会被清空。key 可省略（自动用 syncId）。" +
      "同一 key 已存在时会拒绝，改已有记录请用 update_record。",
    inputSchema: {
      type: "object",
      properties: {
        collection: { type: "string" },
        key: { type: "string", description: "可选；不传则自动生成" },
        payload: { type: "object" },
      },
      required: ["collection", "payload"],
      additionalProperties: false,
    },
  },
  {
    name: "delete_record",
    description:
      "软删除一条记录：写入 deletedAt 墓碑，App 与网页会同步删除，可在回收站恢复。" +
      "不会物理删除，也不会动其它记录。",
    inputSchema: {
      type: "object",
      properties: { collection: { type: "string" }, key: { type: "string" } },
      required: ["collection", "key"],
      additionalProperties: false,
    },
  },
  {
    name: "list_collections",
    description: "列出可管理的集合名、中文含义与写入约束。建记录前先看它，collection 必须取这里的键。",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
  },
  {
    name: "get_settings",
    description: "读取家庭设置（昵称、提醒时间/阶梯、记账月起始日、菜单天数、预算提醒等）。",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
  },
  {
    name: "update_settings",
    description:
      "更新家庭设置：只合并给出的字段，其余保持不变。允许字段见 get_settings；" +
      "菜谱库/周菜单等结构化内容不在白名单内。",
    inputSchema: {
      type: "object",
      properties: { fields: { type: "object" } },
      required: ["fields"],
      additionalProperties: false,
    },
  },
];

async function handleMcp(request, env) {
  // 预检必须在鉴权之前：浏览器不会在 OPTIONS 里带 Authorization。
  if (request.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: MCP_CORS_HEADERS });
  }
  const provided = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "").trim();
  if (!env.MCP_TOKEN || !equalConstantTime(provided, env.MCP_TOKEN)) {
    return new Response(JSON.stringify({ error: "unauthorized" }), {
      status: 401,
      headers: {
        "content-type": "application/json",
        "www-authenticate": "Bearer",
        ...MCP_CORS_HEADERS,
      },
    });
  }
  if (request.method === "GET") {
    // 不支持服务端主动推送（SSE），如实拒绝而不是假装成功。
    return mcpJson({ error: "本服务端不提供 SSE 流" }, 405);
  }
  const message = await request.json().catch(() => null);
  if (!message || typeof message.method !== "string") {
    return mcpJson({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } }, 400);
  }
  const id = message.id ?? null;
  const reply = (result) => mcpJson({ jsonrpc: "2.0", id, result });
  const error = (code, msg) => mcpJson({ jsonrpc: "2.0", id, error: { code, message: msg } });

  if (message.method === "initialize") {
    return reply({
      // 回显客户端版本会让旧客户端误以为服务端支持它；这里固定返回本服务端实现的版本，
      // 由客户端按 MCP 规范决定是否继续。
      protocolVersion: MCP_PROTOCOL_VERSION,
      capabilities: { tools: {} },
      serverInfo: { name: "lemonbox-sync", version: VERSION },
    });
  }
  if (message.method === "notifications/initialized" || message.method === "notifications/cancelled") {
    return new Response(null, { status: 202, headers: MCP_CORS_HEADERS });
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
    const collection = requireMcpCollection(args.collection);
    const key = String(args.key || "").trim();
    const fields = args.fields;
    if (!key) throw new Error("缺少 key");
    if (!fields || typeof fields !== "object" || Array.isArray(fields)) {
      throw new Error("fields 需要是一个字段对象");
    }
    // 跨设备稳定键与墓碑字段不接受 AI 改写：
    //  - id 是本机自增，改了会让别的引用指错行；syncId 是跨设备键，改了就换了一条记录；
    //  - deletedAt / purgedAt 是删除墓碑，删除请用 delete_record；
    //  - updatedAt 由服务端统一写，否则 AI 传一个旧值会让 App 端 LWW 判成「不新」而丢弃修改。
    for (const field of Object.keys(fields)) {
      if (field === "id" || field === "syncId") throw new Error(`不允许修改 ${field}`);
      if (field === "deletedAt" || field === "purgedAt") throw new Error("删除/恢复请用 delete_record");
      if (field === "updatedAt") throw new Error("updatedAt 由服务端写入，不允许外部指定");
    }
    const row = await env.DB.prepare(
      "SELECT payload, server_seq, deleted FROM records WHERE collection = ? AND record_key = ?",
    )
      .bind(collection, key)
      .first();
    if (!row) throw new Error("记录不存在：不允许凭空创建，先用 create_record 或确认 key");
    if (row.deleted) throw new Error("记录已删除；请先在 App 回收站恢复，或不要修改墓碑记录");
    const expectedSeq = Number(args.expectedSeq || 0);
    if (expectedSeq > 0 && Number(row.server_seq) !== expectedSeq) {
      throw new Error(`记录已被其他设备修改（当前 seq ${row.server_seq}），请重新读取后再改`);
    }
    const origin = safeParse(row.payload);
    if (!origin || typeof origin !== "object") throw new Error("原载荷不是合法 JSON，拒绝改写");
    const merged = { ...origin, ...fields, updatedAt: Date.now() };
    // 只覆盖给出的字段；载荷里的 syncId 不允许被改（改了就跨设备指错行）。
    if (merged.syncId !== origin.syncId) throw new Error("不允许修改 syncId");
    const { seq } = await writeMcpRecord(env, {
      collection,
      key,
      payload: merged,
      deleted: false,
      expectedSeq,
    });
    return { ok: true, key, seq, appliedFields: Object.keys(fields), updatedBy: "AI助手" };
  }
  if (name === "create_record") {
    const collection = requireMcpCollection(args.collection);
    const input = args.payload;
    if (!input || typeof input !== "object" || Array.isArray(input)) {
      throw new Error("payload 需要是一个字段对象");
    }
    const key = String(args.key || input.syncId || crypto.randomUUID()).trim();
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(key)) {
      throw new Error("key 只能包含字母、数字、下划线和短横线，长度 1-128");
    }
    const prior = await env.DB.prepare(
      "SELECT 1 AS n FROM records WHERE collection = ? AND record_key = ?",
    )
      .bind(collection, key)
      .first();
    if (prior) throw new Error("记录已存在；修改请用 update_record");
    const payload = {
      ...input,
      id: 0,
      syncId: key,
      updatedAt: Date.now(),
      deletedAt: null,
    };
    if ("purgedAt" in payload) payload.purgedAt = null;
    const { seq } = await writeMcpRecord(env, { collection, key, payload, deleted: false });
    return { ok: true, collection, key, seq, createdBy: "AI助手" };
  }
  if (name === "delete_record") {
    const collection = requireMcpCollection(args.collection);
    const key = String(args.key || "").trim();
    if (!key) throw new Error("缺少 key");
    const row = await env.DB.prepare(
      "SELECT payload, server_seq, deleted FROM records WHERE collection = ? AND record_key = ?",
    )
      .bind(collection, key)
      .first();
    if (!row) throw new Error("记录不存在");
    if (row.deleted) {
      return { ok: true, key, alreadyDeleted: true, seq: Number(row.server_seq) };
    }
    const origin = safeParse(row.payload) || {};
    const merged = { ...origin, deletedAt: Date.now() };
    const { seq } = await writeMcpRecord(env, { collection, key, payload: merged, deleted: true });
    return { ok: true, key, seq, deleted: true };
  }
  if (name === "list_collections") {
    return Object.entries(MCP_COLLECTIONS).map(([name, label]) => ({
      name,
      label,
      note: "create_record 的 payload 参考同类记录；key 用载荷里的 syncId",
    }));
  }
  if (name === "get_settings") {
    const row = await env.DB.prepare(
      "SELECT payload, server_seq, updated_at FROM records " +
        "WHERE collection = 'preferences' AND record_key = 'preferences'",
    ).first();
    if (!row) {
      return { seq: 0, updatedAt: 0, settings: {}, allowedFields: [...MCP_SETTING_FIELDS] };
    }
    const data = safeParse(row.payload) || {};
    const settings = {};
    for (const field of MCP_SETTING_FIELDS) {
      if (field in data) settings[field] = data[field];
    }
    return {
      seq: Number(row.server_seq),
      updatedAt: Number(row.updated_at),
      settings,
      allowedFields: [...MCP_SETTING_FIELDS],
    };
  }
  if (name === "update_settings") {
    const fields = args.fields;
    if (!fields || typeof fields !== "object" || Array.isArray(fields)) {
      throw new Error("fields 需要是一个字段对象");
    }
    for (const field of Object.keys(fields)) {
      if (!MCP_SETTING_FIELDS.has(field)) {
        throw new Error(`不允许修改设置字段 ${field}；可用：${[...MCP_SETTING_FIELDS].join("、")}`);
      }
    }
    const row = await env.DB.prepare(
      "SELECT payload FROM records WHERE collection = 'preferences' AND record_key = 'preferences'",
    ).first();
    const origin = row ? (safeParse(row.payload) || {}) : {};
    const merged = { ...origin, ...fields, updatedAt: Date.now() };
    const { seq } = await writeMcpRecord(env, {
      collection: "preferences",
      key: "preferences",
      payload: merged,
      deleted: false,
    });
    return { ok: true, seq, updatedFields: Object.keys(fields), updatedBy: "AI助手" };
  }
  throw new Error(`未知工具：${name}`);
}

/** 校验 collection 在可管理白名单内，并把错误文案说清可用值。 */
function requireMcpCollection(collection) {
  const name = String(collection || "").trim();
  if (!MCP_COLLECTIONS[name]) {
    throw new Error(
      `不支持的 collection：${name || "(空)"}。可用：${Object.keys(MCP_COLLECTIONS).join("、")}`,
    );
  }
  return name;
}

async function nextServerSeq(env) {
  const row = await env.DB.prepare("UPDATE meta SET v = v + 1 WHERE k = 'seq' RETURNING v").first();
  return Number(row.v);
}

/**
 * 写一条 MCP 记录：统一发号、写 updated_by_user/dev 与时间戳。
 *
 * [expectedSeq] > 0 时用条件 UPDATE 做乐观锁，避免「先读后写」覆盖 App 刚推上来的修改；
 * 0 表示覆盖写（create / settings 整块更新）。
 */
async function writeMcpRecord(env, { collection, key, payload, deleted = false, expectedSeq = 0 }) {
  const text = JSON.stringify(payload);
  if (text.length > PUSH_MAX_RECORD_CHARS) {
    throw new Error(`载荷过大（上限 ${PUSH_MAX_RECORD_CHARS} 字符）`);
  }
  const seq = await nextServerSeq(env);
  const now = Date.now();
  if (expectedSeq > 0) {
    const result = await env.DB.prepare(
      "UPDATE records SET payload = ?, server_seq = ?, updated_by_user = ?, updated_by_dev = ?, " +
        "updated_at = ?, deleted = ? WHERE collection = ? AND record_key = ? AND server_seq = ?",
    )
      .bind(
        text,
        seq,
        "AI助手",
        MCP_DEVICE_ID,
        now,
        deleted ? 1 : 0,
        collection,
        key,
        expectedSeq,
      )
      .run();
    if (!result.meta || Number(result.meta.changes) === 0) {
      throw new Error("记录已被其他设备修改，已拒绝覆盖；请重新读取后再改");
    }
    return { seq, now };
  }
  await env.DB.prepare(
    "INSERT INTO records(collection, record_key, payload, server_seq, updated_by_user, updated_by_dev, updated_at, deleted) " +
      "VALUES(?,?,?,?,?,?,?,?) " +
      "ON CONFLICT(collection, record_key) DO UPDATE SET payload=excluded.payload, server_seq=excluded.server_seq, " +
      "updated_by_user=excluded.updated_by_user, updated_by_dev=excluded.updated_by_dev, " +
      "updated_at=excluded.updated_at, deleted=excluded.deleted",
  )
    .bind(collection, key, text, seq, "AI助手", MCP_DEVICE_ID, now, deleted ? 1 : 0)
    .run();
  return { seq, now };
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

/** 定时清理：过期会话与一天前的登录失败桶；不做业务数据清理。 */
async function cleanupExpired(env) {
  const now = Date.now();
  try {
    await env.DB.batch([
      env.DB.prepare("DELETE FROM sessions WHERE expires_at < ?").bind(now),
      env.DB.prepare("DELETE FROM login_failures WHERE last_at < ?")
        .bind(now - 24 * 60 * 60 * 1000),
    ]);
  } catch (e) {
    // Cron 失败不该影响任何请求；下次再清即可。
  }
}

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
      if (pathname === "/v1/meta" && request.method === "GET") return await handleMeta(request, env);
      if (pathname === "/v1/snapshot" && request.method === "GET") return await handleSnapshot(request, env);
      if (pathname === "/v1/changes" && request.method === "GET") return await handlePull(request, env, session);
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

  async scheduled(event, env, ctx) {
    ctx.waitUntil(cleanupExpired(env));
  },
};
