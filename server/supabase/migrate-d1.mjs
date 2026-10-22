#!/usr/bin/env node
// D1 → Supabase 迁移 dry-run。
//
// 只做本地校验与统计，不连接任何远端、不写数据库、不推进任何水位。
// 用法：
//   node migrate-d1.mjs --input d1-export.json
//   node migrate-d1.mjs --input d1-export.json --emit 0002_import.sql
//
// 输入支持两种形状：
//   {"records":[{"collection":"items","record_key":"...","payload":{...},...}]}
//   或直接的记录数组。
//
// 输出：将写入多少行、跳过多少行、坏行原因；--emit 时额外生成可直接执行的 SQL。
// 注意：生成的 SQL 不含 household_id，执行前需替换 __HOUSEHOLD_ID__。

import { readFileSync, writeFileSync } from 'node:fs';

const REQUIRED = ['collection', 'record_key'];

function parseArgs(argv) {
    const out = { input: '', emit: '' };
    for (let i = 0; i < argv.length; i++) {
        if (argv[i] === '--input') out.input = argv[++i] ?? '';
        else if (argv[i] === '--emit') out.emit = argv[++i] ?? '';
    }
    return out;
}

function normalizePayload(raw) {
    if (raw == null) return { ok: false, reason: 'payload 缺失' };
    if (typeof raw === 'object') return { ok: true, value: raw };
    if (typeof raw === 'string') {
        try {
            const parsed = JSON.parse(raw);
            if (parsed === null || typeof parsed !== 'object') {
                return { ok: false, reason: 'payload 不是对象' };
            }
            return { ok: true, value: parsed };
        } catch {
            return { ok: false, reason: 'payload 不是合法 JSON' };
        }
    }
    return { ok: false, reason: `payload 类型不支持: ${typeof raw}` };
}

function normalizeRecord(record) {
    for (const field of REQUIRED) {
        if (!record || typeof record[field] !== 'string' || record[field].trim() === '') {
            return { ok: false, reason: `${field} 为空` };
        }
    }
    const payload = normalizePayload(record.payload);
    if (!payload.ok) return payload;

    const updatedAt = Number(record.updated_at ?? 0);
    if (!Number.isFinite(updatedAt) || updatedAt < 0) {
        return { ok: false, reason: 'updated_at 非法' };
    }

    return {
        ok: true,
        value: {
            collection: record.collection.trim(),
            record_key: record.record_key.trim(),
            payload: payload.value,
            updated_by_user: String(record.updated_by_user ?? ''),
            updated_by_dev: String(record.updated_by_dev ?? ''),
            updated_at: Math.trunc(updatedAt),
            deleted: record.deleted === true || record.deleted === 1 || record.deleted === '1',
        },
    };
}

function sqlLiteral(value) {
    return `'${String(value).replace(/'/g, "''")}'`;
}

function main() {
    const args = parseArgs(process.argv.slice(2));
    if (!args.input) {
        console.error('用法: node migrate-d1.mjs --input d1-export.json [--emit 0002_import.sql]');
        process.exit(2);
    }

    let raw;
    try {
        raw = JSON.parse(readFileSync(args.input, 'utf8'));
    } catch (error) {
        console.error(`读取失败: ${error.message}`);
        process.exit(1);
    }

    const records = Array.isArray(raw) ? raw : Array.isArray(raw.records) ? raw.records : null;
    if (!records) {
        console.error('输入既不是数组，也没有 records 数组。');
        process.exit(1);
    }

    const seen = new Map();
    const valid = [];
    const skipped = [];
    const byCollection = new Map();

    for (let i = 0; i < records.length; i++) {
        const normalized = normalizeRecord(records[i]);
        if (!normalized.ok) {
            skipped.push({ index: i, reason: normalized.reason });
            continue;
        }
        const row = normalized.value;
        const key = `${row.collection}\u0000${row.record_key}`;
        if (seen.has(key)) {
            skipped.push({ index: i, reason: `重复键，首次出现在第 ${seen.get(key)} 条` });
            continue;
        }
        seen.set(key, i);
        valid.push(row);
        byCollection.set(row.collection, (byCollection.get(row.collection) ?? 0) + 1);
    }

    const deletedCount = valid.filter((row) => row.deleted).length;
    console.log('=== D1 → Supabase dry-run ===');
    console.log(`输入记录: ${records.length}`);
    console.log(`将写入:   ${valid.length}（其中软删 ${deletedCount}）`);
    console.log(`将跳过:   ${skipped.length}`);
    console.log('集合分布:');
    for (const [collection, count] of [...byCollection.entries()].sort()) {
        console.log(`  ${collection.padEnd(24)} ${count}`);
    }

    if (skipped.length > 0) {
        console.log('跳过明细（最多 10 条）:');
        for (const item of skipped.slice(0, 10)) {
            console.log(`  #${item.index}: ${item.reason}`);
        }
    }

    if (args.emit) {
        const lines = [
            '-- 由 migrate-d1.mjs 生成。执行前把 __HOUSEHOLD_ID__ 替换为实际家 id。',
            'begin;',
        ];
        for (const row of valid) {
            lines.push(
                'insert into public.records' +
                '(household_id, collection, record_key, payload, revision,' +
                ' updated_by_user, updated_by_dev, updated_at, deleted) values (' +
                `${sqlLiteral('__HOUSEHOLD_ID__')}, ` +
                `${sqlLiteral(row.collection)}, ${sqlLiteral(row.record_key)}, ` +
                `${sqlLiteral(JSON.stringify(row.payload))}::jsonb, 1, ` +
                `${sqlLiteral(row.updated_by_user)}, ${sqlLiteral(row.updated_by_dev)}, ` +
                `${row.updated_at}, ${row.deleted}) ` +
                'on conflict (household_id, collection, record_key) do nothing;'
            );
        }
        lines.push('commit;', '');
        writeFileSync(args.emit, lines.join('\n'), 'utf8');
        console.log(`已生成 SQL: ${args.emit}`);
    }

    // 有跳过行时以非零退出，避免被当成“全部迁移成功”。
    process.exit(skipped.length > 0 ? 3 : 0);
}

main();
