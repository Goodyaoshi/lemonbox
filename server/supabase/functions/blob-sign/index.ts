// 图片存储签名函数（Supabase Edge Function）。
//
// 只做一件事：验证「这是本家庭成员」后，用 Filebase 的 S3 密钥换签名。
// 密钥只存在于函数 Secrets，数据库里不落明文，客户端也永远拿不到。
//
// 客户端报文（POST JSON）：
//   { op: "sign", key, method: "GET"|"PUT", ttlSeconds }
//   { op: "probe", key }
//   { op: "delete", key }
//   { op: "list", prefix, continuationToken }
//   { op: "selfcheck" }

import { createClient } from "npm:@supabase/supabase-js@2";
import { AwsClient } from "npm:aws4fetch@1.0.20";

const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const S3_ENDPOINT = (Deno.env.get("S3_ENDPOINT") ?? "https://s3.filebase.io").replace(/\/+$/, "");
const S3_BUCKET = Deno.env.get("S3_BUCKET") ?? "lemonbox";
const S3_REGION = Deno.env.get("S3_REGION") ?? "auto";
const S3_ACCESS_KEY = Deno.env.get("S3_ACCESS_KEY") ?? "";
const S3_SECRET_KEY = Deno.env.get("S3_SECRET_KEY") ?? "";

const s3 = new AwsClient({
  accessKeyId: S3_ACCESS_KEY,
  secretAccessKey: S3_SECRET_KEY,
  service: "s3",
  region: S3_REGION,
});

const MAX_TTL_SECONDS = 3600;
const MIN_TTL_SECONDS = 60;

class HttpError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS_HEADERS, "Content-Type": "application/json; charset=utf-8" },
  });
}

/** 对象键按段编码，保持 `images/<sha256>.jpg` 的层级结构。 */
function encodeKey(key: string): string {
  return key
    .split("/")
    .filter((segment) => segment.length > 0)
    .map((segment) => encodeURIComponent(segment))
    .join("/");
}

function objectUrl(key: string): URL {
  if (!key || key.includes("..") || key.startsWith("/")) {
    throw new HttpError(400, "图片路径不合法");
  }
  return new URL(`${S3_ENDPOINT}/${S3_BUCKET}/${encodeKey(key)}`);
}

/** 桶根地址，用于 list 与 selfcheck。 */
function bucketUrl(): URL {
  return new URL(`${S3_ENDPOINT}/${S3_BUCKET}`);
}

async function requireHouseholdMember(req: Request): Promise<string> {
  const url = Deno.env.get("SUPABASE_URL") ?? "";
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  if (!url || !serviceKey) throw new HttpError(500, "函数缺少 Supabase 运行参数");

  const token = (req.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "").trim();
  if (!token) throw new HttpError(401, "缺少登录令牌，请重新登录");

  const admin = createClient(url, serviceKey, { auth: { persistSession: false } });
  const { data, error } = await admin.auth.getUser(token);
  if (error || !data.user) throw new HttpError(401, "登录已过期，请重新登录");

  const { data: member, error: memberError } = await admin
    .from("household_members")
    .select("household_id")
    .eq("user_id", data.user.id)
    .limit(1)
    .maybeSingle();
  if (memberError) throw new HttpError(500, "校验家庭身份失败");
  if (!member) throw new HttpError(403, "这个账号还没有加入家庭");

  return data.user.id;
}

function unescapeXml(value: string): string {
  return value
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, "&");
}

function xmlText(xml: string, tag: string): string | null {
  const match = new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`).exec(xml);
  return match ? match[1] : null;
}

interface ListEntry {
  key: string;
  size: number;
  lastModified: number;
}

function parseListXml(xml: string): {
  objects: ListEntry[];
  truncated: boolean;
  nextContinuationToken: string;
} {
  const objects: ListEntry[] = [];
  for (const match of xml.matchAll(/<Contents>([\s\S]*?)<\/Contents>/g)) {
    const block = match[1];
    const rawKey = xmlText(block, "Key");
    if (!rawKey) continue;
    objects.push({
      key: unescapeXml(rawKey),
      size: Number(xmlText(block, "Size") ?? "0") || 0,
      lastModified: Date.parse(xmlText(block, "LastModified") ?? "") || 0,
    });
  }
  return {
    objects,
    truncated: (xmlText(xml, "IsTruncated") ?? "").trim() === "true",
    nextContinuationToken: (xmlText(xml, "NextContinuationToken") ?? "").trim(),
  };
}

async function handleSign(input: Record<string, unknown>): Promise<Response> {
  const key = String(input.key ?? "");
  const method = String(input.method ?? "GET").toUpperCase();
  if (method !== "GET" && method !== "PUT") throw new HttpError(400, "只支持 GET / PUT");
  const ttl = Math.min(
    MAX_TTL_SECONDS,
    Math.max(MIN_TTL_SECONDS, Number(input.ttlSeconds ?? 300) || 300),
  );

  const url = objectUrl(key);
  url.searchParams.set("X-Amz-Expires", String(ttl));
  // signQuery 把签名放进查询串，客户端拿到即可直连，无需再计算 SigV4。
  const signed = await s3.sign(url, { method, aws: { signQuery: true } });
  return json({
    url: signed.url,
    method,
    expiresAt: Date.now() + ttl * 1000,
  });
}

async function handleProbe(input: Record<string, unknown>): Promise<Response> {
  const key = String(input.key ?? "");
  const res = await s3.fetch(objectUrl(key), { method: "HEAD" });
  const size = Number(res.headers.get("content-length") ?? "0") || 0;
  return json({ exists: res.ok, status: res.status, size });
}

async function handleDelete(input: Record<string, unknown>): Promise<Response> {
  const key = String(input.key ?? "");
  const res = await s3.fetch(objectUrl(key), { method: "DELETE" });
  // 404 表示本来就没有，删除意图已经达成。
  return json({ ok: res.ok || res.status === 404, status: res.status });
}

async function handleList(input: Record<string, unknown>): Promise<Response> {
  const url = bucketUrl();
  url.searchParams.set("list-type", "2");
  const prefix = String(input.prefix ?? "");
  if (prefix) url.searchParams.set("prefix", prefix);
  const token = String(input.continuationToken ?? "");
  if (token) url.searchParams.set("continuation-token", token);

  const res = await s3.fetch(url, { method: "GET" });
  const xml = await res.text();
  if (!res.ok) throw new HttpError(res.status, `列举失败（${res.status}）`);
  return json(parseListXml(xml));
}

async function handleSelfCheck(): Promise<Response> {
  const res = await s3.fetch(bucketUrl(), { method: "HEAD" });
  return json({
    ok: res.ok,
    status: res.status,
    message: res.ok ? "" : `对象存储返回 ${res.status}`,
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: CORS_HEADERS });
  }
  if (req.method !== "POST") return json({ error: "只支持 POST" }, 405);

  try {
    if (!S3_ACCESS_KEY || !S3_SECRET_KEY) {
      throw new HttpError(500, "函数还没有配置 S3 密钥");
    }
    await requireHouseholdMember(req);

    let input: Record<string, unknown>;
    try {
      input = await req.json();
    } catch {
      throw new HttpError(400, "请求体不是合法 JSON");
    }

    const op = String(input.op ?? "");
    switch (op) {
      case "sign":
        return await handleSign(input);
      case "probe":
        return await handleProbe(input);
      case "delete":
        return await handleDelete(input);
      case "list":
        return await handleList(input);
      case "selfcheck":
        return await handleSelfCheck();
      default:
        throw new HttpError(400, `未知操作：${op || "空"}`);
    }
  } catch (error) {
    if (error instanceof HttpError) return json({ error: error.message }, error.status);
    const message = error instanceof Error ? error.message : "对象存储请求失败";
    return json({ error: message }, 500);
  }
});
