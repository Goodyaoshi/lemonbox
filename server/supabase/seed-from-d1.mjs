#!/usr/bin/env node
// 把 D1 的「当前快照」铺进 Supabase，只迁现状，不迁历史。
//
// 做法：
//   1. 用旧 Worker 的 /v1/login 登录，读 /v1/meta 拿到当前全局序号；
//   2. 按集合走 /v1/snapshot 逐页读**活数据**（deleted=0），不碰 change_log；
//   3. 用 Supabase 的邮箱账号登录，逐批调 /rest/v1/rpc/apply_changes（force=true），
//      每批 100 条。服务端会为每条追加一条 change_log——那是铺底本身，不是旧历史。
//
// 为什么不直接写 SQL：没有 service_role，也不该有；走 apply_changes 与 App 的写路径完全一致，
// 先生效再回读，能当场验证 RLS、成员关系与函数签名。
//
// 幂等：payload 相同的重复执行会被服务端判成 noop，不会产生第二条 change_log。
// 保护：Supabase 已有数据时默认拒绝执行，确认要覆盖再加 --reseed。
//
// 用法：
//   node seed-from-d1.mjs --d1-password '<旧 Worker 密码>' --supa-password '<Supabase 密码>'
//   node seed-from-d1.mjs ... --dry-run
// 可覆盖的地址/账号（默认见下）：
//   --d1-base / --d1-user / --supa-base / --supa-email / --batch

const DEFAULTS = {
    d1Base: 'https://lemonbox.goodyaoshi.eu.cc',
    d1User: '时天',
    supaBase: 'https://sgqvsxowzkqbogknodaj.supabase.co',
    supaKey: 'sb_publishable_lmgqjXC0mF7MLozkLF-HwA_O7xhiezA',
    supaEmail: 'shitian@lemonbox.app',
    batch: 100,
};

function parseArgs(argv) {
    const out = { ...DEFAULTS, dryRun: false, reseed: false };
    const map = {
        '--d1-base': 'd1Base', '--d1-user': 'd1User', '--d1-password': 'd1Password',
        '--supa-base': 'supaBase', '--supa-key': 'supaKey',
        '--supa-email': 'supaEmail', '--supa-password': 'supaPassword',
        '--batch': 'batch',
    };
    for (let i = 0; i < argv.length; i++) {
        const key = map[argv[i]];
        if (key) out[key] = argv[++i] ?? '';
        else if (argv[i] === '--dry-run') out.dryRun = true;
        else if (argv[i] === '--reseed') out.reseed = true;
    }
    out.d1Password = out.d1Password || process.env.D1_PASSWORD || '';
    out.supaPassword = out.supaPassword || process.env.SUPA_PASSWORD || '';
    out.batch = Math.max(1, Math.min(200, Number(out.batch) || DEFAULTS.batch));
    return out;
}

async function readJson(response, what) {
    const text = await response.text();
    let body = null;
    try { body = text ? JSON.parse(text) : null; } catch { /* 下面统一报 */ }
    if (!response.ok) {
        const message = body?.error || body?.message || body?.msg || text.slice(0, 200);
        throw new Error(`${what} 失败（HTTP ${response.status}）：${message}`);
    }
    return body;
}

async function loginD1(cfg) {
    const response = await fetch(`${cfg.d1Base}/v1/login`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
            username: cfg.d1User,
            password: cfg.d1Password,
            deviceId: 'seed-d1',
            deviceName: 'D1 快照铺底',
        }),
    });
    const body = await readJson(response, 'D1 登录');
    if (!body?.token) throw new Error('D1 登录没有返回令牌');
    return body.token;
}

async function loginSupabase(cfg) {
    const response = await fetch(`${cfg.supaBase}/auth/v1/token?grant_type=password`, {
        method: 'POST',
        headers: { apikey: cfg.supaKey, 'content-type': 'application/json' },
        body: JSON.stringify({ email: cfg.supaEmail, password: cfg.supaPassword }),
    });
    const body = await readJson(response, 'Supabase 登录');
    if (!body?.access_token) throw new Error('Supabase 登录没有返回 access_token');
    return body.access_token;
}

async function d1Meta(cfg, token) {
    const response = await fetch(`${cfg.d1Base}/v1/meta?collections=1`, {
        headers: { authorization: `Bearer ${token}` },
    });
    return readJson(response, '读取 D1 meta');
}

async function d1SnapshotPage(cfg, token, collection, cursor, upTo) {
    const query = new URLSearchParams({
        collection, cursor: String(cursor), upTo: String(upTo), limit: '500',
    });
    const response = await fetch(`${cfg.d1Base}/v1/snapshot?${query}`, {
        headers: { authorization: `Bearer ${token}` },
    });
    return readJson(response, `读取 D1 快照 ${collection}`);
}

function normalizePayload(raw) {
    if (raw == null) return null;
    if (typeof raw === 'object') return raw;
    if (typeof raw === 'string') {
        try {
            const parsed = JSON.parse(raw);
            return parsed && typeof parsed === 'object' ? parsed : null;
        } catch { return null; }
    }
    return null;
}

async function supaMeta(cfg, token) {
    const response = await fetch(`${cfg.supaBase}/rest/v1/rpc/sync_meta`, {
        method: 'POST',
        headers: {
            apikey: cfg.supaKey,
            authorization: `Bearer ${token}`,
            'content-type': 'application/json',
        },
        body: JSON.stringify({ p_collections: true }),
    });
    return readJson(response, '读取 Supabase meta');
}

async function supaHousehold(cfg, token) {
    const response = await fetch(
        `${cfg.supaBase}/rest/v1/household_members?select=household_id&limit=1`,
        { headers: { apikey: cfg.supaKey, authorization: `Bearer ${token}` } },
    );
    const rows = await readJson(response, '读取 household');
    const id = Array.isArray(rows) ? rows[0]?.household_id : null;
    if (!id) throw new Error('当前账号还没有加入家庭，请先执行 0002_bind_family.sql');
    return id;
}

async function seedBatch(cfg, token, household, changes) {
    const response = await fetch(`${cfg.supaBase}/rest/v1/rpc/apply_changes`, {
        method: 'POST',
        headers: {
            apikey: cfg.supaKey,
            authorization: `Bearer ${token}`,
            'content-type': 'application/json',
        },
        body: JSON.stringify({
            p_batch: {
                household_id: household,
                device_id: 'seed-d1',
                actor: cfg.d1User,
                changes,
            },
        }),
    });
    const body = await readJson(response, '写入 Supabase');
    const applied = (body?.applied ?? []).filter((item) => !item.noop).length;
    const noop = (body?.applied ?? []).filter((item) => item.noop).length;
    const conflicts = (body?.conflicts ?? []).length;
    return { applied, noop, conflicts };
}

async function main() {
    const cfg = parseArgs(process.argv.slice(2));
    if (!cfg.d1Password) throw new Error('缺少 --d1-password（旧 Worker 的登录密码）');
    if (!cfg.supaPassword) throw new Error('缺少 --supa-password（Supabase 账号密码）');

    console.log('=== D1 当前快照 → Supabase ===');
    const d1Token = await loginD1(cfg);
    console.log(`D1 登录成功：${cfg.d1User}`);

    const meta = await d1Meta(cfg, d1Token);
    const upTo = Number(meta?.seq ?? 0);
    const collections = (meta?.collections ?? []).map((item) => item.collection).filter(Boolean);
    console.log(`D1 当前序号 ${upTo}，集合 ${collections.length} 个`);

    const rows = [];
    let dropped = 0;
    for (const collection of collections.sort()) {
        let cursor = 0;
        let count = 0;
        for (;;) {
            const page = await d1SnapshotPage(cfg, d1Token, collection, cursor, upTo);
            for (const change of page?.changes ?? []) {
                if (change?.deleted === true) continue;
                const payload = normalizePayload(change?.payload);
                if (!payload || !change?.key) {
                    dropped += 1;
                    console.warn(`  跳过坏载荷：${collection}/${change?.key ?? '(无键)'}`);
                    continue;
                }
                rows.push({
                    collection: change.collection || collection,
                    key: String(change.key),
                    payload,
                    updatedAt: Number(change.updatedAt ?? 0),
                    updatedBy: String(change.updatedBy ?? ''),
                });
                count += 1;
            }
            const next = Number(page?.nextCursor ?? cursor);
            if (page?.done || next <= cursor) break;
            cursor = next;
        }
        console.log(`  ${collection.padEnd(26)} ${count}`);
    }
    console.log(`快照合计 ${rows.length} 条（跳过 ${dropped}）`);

    if (cfg.dryRun) {
        console.log('--dry-run：不写 Supabase，结束');
        return;
    }
    if (rows.length === 0) {
        console.log('D1 没有可迁移的活数据，结束');
        return;
    }

    const supaToken = await loginSupabase(cfg);
    console.log(`Supabase 登录成功：${cfg.supaEmail}`);

    const before = await supaMeta(cfg, supaToken);
    if (Number(before?.seq ?? 0) > 0 && !cfg.reseed) {
        throw new Error(`Supabase 已有数据（seq=${before.seq}），确要覆盖请加 --reseed`);
    }
    const household = await supaHousehold(cfg, supaToken);
    console.log(`household=${household}`);

    let applied = 0;
    let noop = 0;
    let conflicts = 0;
    for (let start = 0; start < rows.length; start += cfg.batch) {
        const slice = rows.slice(start, start + cfg.batch);
        const result = await seedBatch(
            cfg,
            supaToken,
            household,
            slice.map((row) => ({
                collection: row.collection,
                record_key: row.key,
                base_revision: 0,
                payload: row.payload,
                deleted: false,
                updated_at: row.updatedAt,
                updated_by_user: row.updatedBy,
                force: true,
            })),
        );
        applied += result.applied;
        noop += result.noop;
        conflicts += result.conflicts;
        console.log(`  已写入 ${Math.min(start + slice.length, rows.length)}/${rows.length}`);
    }

    const after = await supaMeta(cfg, supaToken);
    console.log('--- 结果 ---');
    console.log(`新增 ${applied}，noop ${noop}，冲突 ${conflicts}`);
    console.log(`Supabase seq=${after.seq}`);
    for (const item of after.collections ?? []) {
        console.log(`  ${String(item.collection).padEnd(26)} ${item.count}`);
    }
    if (dropped > 0) {
        console.log(`有 ${dropped} 条坏载荷被跳过，请人工确认`);
        process.exitCode = 4;
    }
}

main().catch((error) => {
    console.error(`失败：${error.message}`);
    process.exit(1);
});
