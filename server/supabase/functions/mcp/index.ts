// 家庭数据 MCP 服务端（Supabase Edge Function）。
//
// 为什么放在 Supabase：App 的数据都落在 Supabase，MCP 的写入必须走同一条发号路径，
// 否则会让 App 永远看不到修改。这里所有写入都经 mcp_apply_changes()
// 走 records + change_log，和客户端推送是同一条发号路径。
//
// 部署：
//   supabase functions deploy mcp --no-verify-jwt
//   supabase secrets set MCP_TOKEN=<家庭级访问令牌>
//   （可选）supabase secrets set MCP_HOUSEHOLD_ID=<家庭 uuid>；不设时取第一个家。
//
// 鉴权：Authorization: Bearer <MCP_TOKEN>。不要复用 Supabase 的 anon/service key。
// MCP_TOKEN 等价于家庭全部数据的读写权限，只放在可信的 AI 客户端配置里。

import { createClient } from "npm:@supabase/supabase-js@2";

const MCP_PROTOCOL_VERSION = "2025-06-18";
const MAX_RECORD_CHARS = 256 * 1024;
const MCP_DEVICE_ID = "mcp";
const MCP_ACTOR = "AI助手";

const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, apikey, content-type, mcp-protocol-version, mcp-session-id",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const MCP_TOKEN = Deno.env.get("MCP_TOKEN") ?? "";
const FIXED_HOUSEHOLD = (Deno.env.get("MCP_HOUSEHOLD_ID") ?? "").trim();

const admin = createClient(SUPABASE_URL, SERVICE_KEY, {
  auth: { persistSession: false, autoRefreshToken: false },
});

/** MCP 可管理的集合（与 App 的 SyncCollections 对齐）。 */
const MCP_COLLECTIONS: Record<string, string> = {
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
 * 让 AI 整块覆盖会轻易毁掉用户菜谱库。
 */
const SETTING_FIELDS = new Set([
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

const TOOLS = [
  {
    name: "sync_status",
    description:
      "查看同步中枢的整体状态：序号水位、各集合记录数（在用/已删）、各设备最后同步到的序号、未决冲突数。",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
  },
  {
    name: "list_records",
    description:
      "按集合列出记录。collection 取值如 items（家当）、reminders（待办提醒）、ledger_records（记账）、categories（分类）、locations（存放地）。",
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
    description: "取单条记录的完整载荷（JSON），改之前先读它。",
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

class ToolError extends Error {}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS_HEADERS, "Content-Type": "application/json; charset=utf-8" },
  });
}

function rpcReply(id: unknown, result: unknown): Response {
  return json({ jsonrpc: "2.0", id, result });
}

function rpcError(id: unknown, code: number, message: string): Response {
  return json({ jsonrpc: "2.0", id, error: { code, message } });
}

/** 定长比较，避免按字符提前返回带来的时序差异。 */
function secureEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

async function resolveHousehold(): Promise<string> {
  if (FIXED_HOUSEHOLD) return FIXED_HOUSEHOLD;
  const { data, error } = await admin
    .from("households")
    .select("id")
    .order("created_at", { ascending: true })
    .limit(1)
    .maybeSingle();
  if (error) throw new Error(`读取家庭失败：${error.message}`);
  if (!data?.id) throw new Error("还没有家庭，请先在 App 里完成首次同步");
  return data.id as string;
}

function requireCollection(value: unknown): string {
  const name = String(value ?? "").trim();
  if (!MCP_COLLECTIONS[name]) {
    throw new ToolError(
      `不支持的 collection：${name || "(空)"}。可用：${Object.keys(MCP_COLLECTIONS).join("、")}`,
    );
  }
  return name;
}

function asObject(value: unknown, label: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new ToolError(`${label} 需要是一个字段对象`);
  }
  return value as Record<string, unknown>;
}

async function readRecord(household: string, collection: string, key: string) {
  const { data, error } = await admin
    .from("records")
    .select("record_key,payload,revision,deleted,updated_by_user,updated_at")
    .eq("household_id", household)
    .eq("collection", collection)
    .eq("record_key", key)
    .maybeSingle();
  if (error) throw new Error(`读取记录失败：${error.message}`);
  return data;
}

/**
 * 写一条记录，统一走 mcp_apply_changes。
 *
 * baseRevision 传当前 revision 表示“以我的读数为准覆盖”；并发写入会变成 conflict，
 * 由调用方提示重新读取，不会静默覆盖别人刚改的内容。
 */
async function writeRecord(
  household: string,
  collection: string,
  key: string,
  payload: Record<string, unknown>,
  deleted: boolean,
  baseRevision: number,
): Promise<number> {
  if (JSON.stringify(payload).length > MAX_RECORD_CHARS) {
    throw new ToolError(`载荷过大（上限 ${MAX_RECORD_CHARS} 字符）`);
  }
  const { data, error } = await admin.rpc("mcp_apply_changes", {
    p_household: household,
    p_changes: [
      {
        collection,
        record_key: key,
        base_revision: baseRevision,
        payload,
        deleted,
        updated_at: Date.now(),
        force: false,
      },
    ],
  });
  if (error) throw new Error(`写入失败：${error.message}`);

  const result = data as {
    applied?: Array<{ seq?: number; noop?: boolean }>;
    conflicts?: Array<{ seq?: number }>;
  };
  if (result?.conflicts?.length) {
    const seq = result.conflicts[0]?.seq ?? 0;
    throw new ToolError(`记录已被其他设备修改（当前 seq ${seq}），请重新读取后再改`);
  }
  return Number(result?.applied?.[0]?.seq ?? 0);
}

async function callTool(name: string, args: Record<string, unknown>) {
  const household = await resolveHousehold();

  if (name === "sync_status") {
    const { data, error } = await admin.rpc("mcp_sync_status", { p_household: household });
    if (error) throw new Error(`读取状态失败：${error.message}`);
    return data;
  }

  if (name === "list_records") {
    const collection = String(args.collection ?? "").trim();
    if (!collection) throw new ToolError("缺少 collection");
    const limit = Math.min(Math.max(1, Number(args.limit ?? 50)), 200);
    let query = admin
      .from("records")
      .select("record_key,payload,revision,deleted,updated_by_user,updated_at")
      .eq("household_id", household)
      .eq("collection", collection)
      .order("revision", { ascending: false })
      .limit(limit);
    if (!args.includeDeleted) query = query.eq("deleted", false);
    const { data, error } = await query;
    if (error) throw new Error(`读取记录失败：${error.message}`);
    return (data ?? []).map((row) => ({
      key: row.record_key,
      seq: Number(row.revision),
      deleted: !!row.deleted,
      updatedBy: row.updated_by_user,
      updatedAt: Number(row.updated_at),
      payload: row.payload,
    }));
  }

  if (name === "get_record") {
    const collection = String(args.collection ?? "").trim();
    const key = String(args.key ?? "").trim();
    if (!collection || !key) throw new ToolError("缺少 collection 或 key");
    const row = await readRecord(household, collection, key);
    if (!row) throw new ToolError("记录不存在（key 要用载荷里的 syncId，不是本机自增 id）");
    return {
      key: row.record_key,
      seq: Number(row.revision),
      deleted: !!row.deleted,
      updatedBy: row.updated_by_user,
      payload: row.payload,
    };
  }

  if (name === "update_record") {
    const collection = requireCollection(args.collection);
    const key = String(args.key ?? "").trim();
    if (!key) throw new ToolError("缺少 key");
    const fields = asObject(args.fields, "fields");

    // 跨设备稳定键与墓碑字段不接受 AI 改写。
    for (const field of Object.keys(fields)) {
      if (field === "id" || field === "syncId") throw new ToolError(`不允许修改 ${field}`);
      if (field === "deletedAt" || field === "purgedAt") throw new ToolError("删除/恢复请用 delete_record");
      if (field === "updatedAt") throw new ToolError("updatedAt 由服务端写入，不允许外部指定");
    }

    const row = await readRecord(household, collection, key);
    if (!row) throw new ToolError("记录不存在：不允许凭空创建，先用 create_record 或确认 key");
    if (row.deleted) throw new ToolError("记录已删除；请先在 App 回收站恢复，或不要修改墓碑记录");

    const expectedSeq = Number(args.expectedSeq ?? 0);
    if (expectedSeq > 0 && Number(row.revision) !== expectedSeq) {
      throw new ToolError(`记录已被其他设备修改（当前 seq ${row.revision}），请重新读取后再改`);
    }

    const origin = asObject(row.payload, "原载荷");
    const merged = { ...origin, ...fields, updatedAt: Date.now() };
    if (merged.syncId !== origin.syncId) throw new ToolError("不允许修改 syncId");

    const seq = await writeRecord(household, collection, key, merged, false, Number(row.revision));
    return { ok: true, key, seq, appliedFields: Object.keys(fields), updatedBy: MCP_ACTOR };
  }

  if (name === "create_record") {
    const collection = requireCollection(args.collection);
    const input = asObject(args.payload, "payload");
    const key = String(args.key ?? input.syncId ?? crypto.randomUUID()).trim();
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(key)) {
      throw new ToolError("key 只能包含字母、数字、下划线和短横线，长度 1-128");
    }
    if (await readRecord(household, collection, key)) {
      throw new ToolError("记录已存在；修改请用 update_record");
    }

    const payload: Record<string, unknown> = {
      ...input,
      id: 0,
      syncId: key,
      updatedAt: Date.now(),
      deletedAt: null,
    };
    if ("purgedAt" in payload) payload.purgedAt = null;

    const seq = await writeRecord(household, collection, key, payload, false, 0);
    return { ok: true, collection, key, seq, createdBy: MCP_ACTOR };
  }

  if (name === "delete_record") {
    const collection = requireCollection(args.collection);
    const key = String(args.key ?? "").trim();
    if (!key) throw new ToolError("缺少 key");
    const row = await readRecord(household, collection, key);
    if (!row) throw new ToolError("记录不存在");
    if (row.deleted) return { ok: true, key, alreadyDeleted: true, seq: Number(row.revision) };

    const origin = asObject(row.payload, "原载荷");
    const merged = { ...origin, deletedAt: Date.now() };
    const seq = await writeRecord(household, collection, key, merged, true, Number(row.revision));
    return { ok: true, key, seq, deleted: true };
  }

  if (name === "list_collections") {
    return Object.entries(MCP_COLLECTIONS).map(([key, label]) => ({
      name: key,
      label,
      note: "create_record 的 payload 参考同类记录；key 用载荷里的 syncId",
    }));
  }

  if (name === "get_settings") {
    const row = await readRecord(household, "preferences", "preferences");
    if (!row) {
      return { seq: 0, updatedAt: 0, settings: {}, allowedFields: [...SETTING_FIELDS] };
    }
    const data = (row.payload ?? {}) as Record<string, unknown>;
    const settings: Record<string, unknown> = {};
    for (const field of SETTING_FIELDS) if (field in data) settings[field] = data[field];
    return {
      seq: Number(row.revision),
      updatedAt: Number(row.updated_at),
      settings,
      allowedFields: [...SETTING_FIELDS],
    };
  }

  if (name === "update_settings") {
    const fields = asObject(args.fields, "fields");
    for (const field of Object.keys(fields)) {
      if (!SETTING_FIELDS.has(field)) {
        throw new ToolError(`不允许修改设置字段 ${field}；可用：${[...SETTING_FIELDS].join("、")}`);
      }
    }
    const row = await readRecord(household, "preferences", "preferences");
    const origin = (row?.payload ?? {}) as Record<string, unknown>;
    const merged = { ...origin, ...fields, updatedAt: Date.now() };
    const seq = await writeRecord(
      household,
      "preferences",
      "preferences",
      merged,
      false,
      Number(row?.revision ?? 0),
    );
    return { ok: true, seq, updatedFields: Object.keys(fields), updatedBy: MCP_ACTOR };
  }

  throw new ToolError(`未知工具：${name}`);
}

export default {
  async fetch(request: Request): Promise<Response> {
    // 预检必须在鉴权之前：浏览器不会在 OPTIONS 里带 Authorization。
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    if (!SUPABASE_URL || !SERVICE_KEY || !MCP_TOKEN) {
      return json({ error: "函数缺少 SUPABASE_URL / SERVICE_ROLE_KEY / MCP_TOKEN" }, 500);
    }

    const provided = (request.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "").trim();
    if (!secureEqual(provided, MCP_TOKEN)) {
      return new Response(JSON.stringify({ error: "unauthorized" }), {
        status: 401,
        headers: {
          "content-type": "application/json",
          "www-authenticate": "Bearer",
          ...CORS_HEADERS,
        },
      });
    }

    if (request.method === "GET") {
      // 不支持服务端主动推送（SSE），如实拒绝。
      return json({ error: "本服务端不提供 SSE 流" }, 405);
    }

    const message = await request.json().catch(() => null);
    if (!message || typeof message.method !== "string") {
      return rpcError(null, -32700, "Parse error");
    }
    const id = message.id ?? null;

    if (message.method === "initialize") {
      return rpcReply(id, {
        protocolVersion: MCP_PROTOCOL_VERSION,
        capabilities: { tools: {} },
        serverInfo: { name: "lemonbox-supabase", version: "1.0.0" },
      });
    }
    if (message.method === "notifications/initialized" || message.method === "notifications/cancelled") {
      return new Response(null, { status: 202, headers: CORS_HEADERS });
    }
    if (message.method === "ping") return rpcReply(id, {});
    if (message.method === "tools/list") return rpcReply(id, { tools: TOOLS });
    if (message.method !== "tools/call") {
      return rpcError(id, -32601, `未知方法：${message.method}`);
    }

    const name = message.params?.name;
    const args = (message.params?.arguments ?? {}) as Record<string, unknown>;
    try {
      const result = await callTool(String(name ?? ""), args);
      return rpcReply(id, {
        content: [{ type: "text", text: JSON.stringify(result, null, 2) }],
      });
    } catch (e) {
      const text = e instanceof Error ? e.message : String(e);
      return rpcReply(id, { content: [{ type: "text", text: `调用失败：${text}` }], isError: true });
    }
  },
};
