-- 柠檬百宝箱 · Supabase 同步底座（P0 / Phase A）
-- 在 Supabase SQL Editor 中按文件顺序执行；本文件幂等，可重复执行。
--
-- 设计要点（与 server/schema.sql 的 D1 版本对齐，但去掉行写入计费带来的写放大）：
--   1. records 仍是 14 个集合的权威副本，payload 用 jsonb 原样承接，迁移零改模型。
--   2. 每行带 revision 做乐观锁；每次真正生效的写入追加一条 change_log，作为唯一增量游标。
--      revision 与 change_log.revision **同值**（全局单调），因此同一个数既能当
--      客户端的拉取水位，又能当推送时的 baseSeq，不需要两套编号。
--   3. 内容未变化的写入返回 noop，不写 change_log，避免“同步一次就把额度打满”。
--   4. 删除只做软删并留痕；墓碑由设备水位决定何时清理，不再固定 30 天。
--   5. 所有业务表按 household_id 做 RLS；写入只经 apply_changes()，客户端不直接写表。
--   6. 网络层返回体直接复用 App 现有的 DTO（PushResponse / PullResponse / ...），
--      安卓端只做“调 RPC + 反序列化”，不写第二套同步逻辑。

-- ---------------------------------------------------------------------------
-- 0. 扩展
-- ---------------------------------------------------------------------------
create extension if not exists pgcrypto;  -- gen_random_uuid()

-- ---------------------------------------------------------------------------
-- 1. 家庭与成员
-- ---------------------------------------------------------------------------
create table if not exists public.households (
    id         uuid primary key default gen_random_uuid(),
    name       text not null default '我们的家',
    created_at timestamptz not null default now()
);

-- 一个账号可以属于多个家（当前只有一个），所有隔离都以 household_id 为准。
create table if not exists public.household_members (
    household_id uuid not null references public.households(id) on delete cascade,
    user_id      uuid not null references auth.users(id) on delete cascade,
    role         text not null default 'member' check (role in ('owner', 'member')),
    display_name text not null default '',
    created_at   timestamptz not null default now(),
    primary key (household_id, user_id)
);

create index if not exists idx_household_members_user
    on public.household_members(user_id);

-- RLS 判定函数。security definer 是为了避免 household_members 自身的 RLS 递归；
-- search_path 固定，防止被调用方劫持同名对象。
create or replace function public.is_household_member(p_household uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1 from public.household_members m
        where m.household_id = p_household
          and m.user_id = auth.uid()
    );
$$;

-- 当前账号所属的家。两台设备属于同一个家，这是所有 RPC 的隔离边界。
create or replace function public.current_household_id()
returns uuid
language sql
stable
security definer
set search_path = public
as $$
    select m.household_id
      from public.household_members m
     where m.user_id = auth.uid()
     order by m.created_at
     limit 1;
$$;

-- ---------------------------------------------------------------------------
-- 2. 记录权威表（14 个集合的载体）
-- ---------------------------------------------------------------------------
create table if not exists public.records (
    household_id    uuid not null references public.households(id) on delete cascade,
    collection      text not null,
    record_key      text not null,          -- 一律用 syncId，绝不用本机自增 id
    payload         jsonb not null default '{}'::jsonb,
    revision        bigint not null default 1,  -- 与最后一次 change_log.revision 同值
    updated_by_user text not null default '',   -- 人：显示“这条最后是谁改的”
    updated_by_dev  text not null default '',   -- 设备：拉取时排除自己
    updated_at      bigint not null default 0,  -- epoch millis，沿用既有载荷语义
    deleted         boolean not null default false,
    primary key (household_id, collection, record_key)
);

create index if not exists idx_records_household_dev
    on public.records(household_id, updated_by_dev);

create index if not exists idx_records_household_collection_rev
    on public.records(household_id, collection, revision);

-- ---------------------------------------------------------------------------
-- 3. 设置：一个 key 一行，替代整块 preferences（整块同步的冲突粒度太粗）
-- ---------------------------------------------------------------------------
create table if not exists public.household_settings (
    household_id    uuid not null references public.households(id) on delete cascade,
    key             text not null,
    value           jsonb not null default 'null'::jsonb,
    revision        bigint not null default 1,
    updated_by_dev  text not null default '',
    updated_at      bigint not null default 0,
    primary key (household_id, key)
);

-- ---------------------------------------------------------------------------
-- 4. 变更日志：增量拉取的唯一游标（删除也留痕）
-- ---------------------------------------------------------------------------
create table if not exists public.change_log (
    revision        bigserial primary key,
    household_id    uuid not null references public.households(id) on delete cascade,
    collection      text not null,
    record_key      text not null,
    deleted         boolean not null default false,
    updated_by_user text not null default '',
    updated_by_dev  text not null default '',
    updated_at      bigint not null default 0,
    changed_at      timestamptz not null default now()
);

create index if not exists idx_change_log_household_revision
    on public.change_log(household_id, revision);

-- ---------------------------------------------------------------------------
-- 5. 设备与冲突台账
-- ---------------------------------------------------------------------------
create table if not exists public.devices (
    household_id      uuid not null references public.households(id) on delete cascade,
    device_id         text not null,
    user_id           uuid references auth.users(id) on delete set null,
    name              text not null default '',
    last_seen_at      bigint not null default 0,
    last_pull_revision bigint not null default 0,
    primary key (household_id, device_id)
);

create table if not exists public.conflicts (
    id              bigserial primary key,
    household_id    uuid not null references public.households(id) on delete cascade,
    collection      text not null,
    record_key      text not null,
    base_revision   bigint not null default 0,
    server_revision bigint not null default 0,
    by_device       text not null default '',
    created_at      timestamptz not null default now()
);

create index if not exists idx_conflicts_household_created
    on public.conflicts(household_id, created_at desc);

-- ---------------------------------------------------------------------------
-- 6. 推送：一批变更 = 一个事务，逐条乐观锁 + 逐条回执
-- ---------------------------------------------------------------------------
-- 入参：
-- {
--   "household_id": "uuid",
--   "device_id": "设备标识",
--   "actor": "时天",
--   "changes": [
--     {"collection":"items","record_key":"<syncId>","base_revision":0,
--      "payload":{...},"deleted":false,"updated_at":1730000000000,"force":false}
--   ]
-- }
-- 回执（与 App 的 PushResponse 一致）：
-- {"applied":[{"collection":"items","key":"...","seq":12,"noop":false}],
--  "conflicts":[{"collection":"items","key":"...","baseSeq":3,"seq":12,
--                "payload":"{...}","deleted":false,"updatedBy":"柠檬"}],
--  "lastSeq":12,"hasMore":false}
--
-- 关键约束：
--   * base_revision 必须等于服务端当前 revision，否则 conflict，绝不静默覆盖；
--     force=true 是用户在诊断页明确选择的「以本机为准」，才跳过这项检查。
--   * 载荷与现状完全一致时 noop，不推进 revision、不写 change_log。
--   * 冲突回执必须带上服务端当前 payload/deleted：App 需要据此决定偏好整块是否落地。
create or replace function public.apply_changes(p_batch jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_household  uuid;
    v_device     text;
    v_actor      text;
    v_change     jsonb;
    v_coll       text;
    v_key        text;
    v_base       bigint;
    v_payload    jsonb;
    v_deleted    boolean;
    v_updated_at bigint;
    v_force      boolean;
    v_current    public.records%rowtype;
    v_exists     boolean;
    v_applied    jsonb := '[]'::jsonb;
    v_conflicts  jsonb := '[]'::jsonb;
    v_rev        bigint;
    v_last_seq   bigint := 0;
begin
    if auth.uid() is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    v_household := (p_batch->>'household_id')::uuid;
    v_device    := coalesce(p_batch->>'device_id', '');
    v_actor     := coalesce(p_batch->>'actor', '');

    if not public.is_household_member(v_household) then
        raise exception 'not a member of household %', v_household using errcode = '42501';
    end if;

    if jsonb_typeof(p_batch->'changes') <> 'array' then
        raise exception 'changes must be an array';
    end if;

    for v_change in select * from jsonb_array_elements(p_batch->'changes')
    loop
        v_coll       := v_change->>'collection';
        v_key        := v_change->>'record_key';
        v_base       := coalesce((v_change->>'base_revision')::bigint, 0);
        v_payload    := coalesce(v_change->'payload', '{}'::jsonb);
        v_deleted    := coalesce((v_change->>'deleted')::boolean, false);
        v_updated_at := coalesce((v_change->>'updated_at')::bigint, 0);
        v_force      := coalesce((v_change->>'force')::boolean, false);

        if v_coll is null or v_coll = '' or v_key is null or v_key = '' then
            raise exception 'collection and record_key are required';
        end if;

        select * into v_current
        from public.records r
        where r.household_id = v_household
          and r.collection = v_coll
          and r.record_key = v_key
        for update;

        v_exists := found;

        if v_exists and v_base <> v_current.revision and not v_force then
            -- 版本落后：记台账并回执服务端当前版本，由客户端决定重推还是落地。
            insert into public.conflicts(household_id, collection, record_key,
                                         base_revision, server_revision, by_device)
            values (v_household, v_coll, v_key, v_base, v_current.revision, v_device);
            v_rev := v_current.revision;
            v_conflicts := v_conflicts || jsonb_build_object(
                'collection', v_coll,
                'key', v_key,
                'baseSeq', v_base,
                'seq', v_current.revision,
                'payload', v_current.payload::text,
                'deleted', v_current.deleted,
                'updatedBy', v_current.updated_by_user
            );
        elsif v_exists
              and v_current.deleted = v_deleted
              and v_current.payload is not distinct from v_payload then
            -- 幂等重放：内容没变，不推进 revision、不写日志。
            v_rev := v_current.revision;
            v_applied := v_applied || jsonb_build_object(
                'collection', v_coll, 'key', v_key, 'seq', v_rev, 'noop', true
            );
        else
            -- 新行或真正的内容变化：先取全局序号，再让 records 与 change_log 用同一个值。
            v_rev := nextval('public.change_log_revision_seq');

            if v_exists then
                update public.records r
                   set payload = v_payload,
                       revision = v_rev,
                       updated_by_user = v_actor,
                       updated_by_dev = v_device,
                       updated_at = v_updated_at,
                       deleted = v_deleted
                 where r.household_id = v_household
                   and r.collection = v_coll
                   and r.record_key = v_key;
            else
                insert into public.records(household_id, collection, record_key, payload,
                                           revision, updated_by_user, updated_by_dev,
                                           updated_at, deleted)
                values (v_household, v_coll, v_key, v_payload, v_rev, v_actor, v_device,
                        v_updated_at, v_deleted);
            end if;

            insert into public.change_log(revision, household_id, collection, record_key,
                                          deleted, updated_by_user, updated_by_dev, updated_at)
            values (v_rev, v_household, v_coll, v_key, v_deleted, v_actor, v_device, v_updated_at);

            v_applied := v_applied || jsonb_build_object(
                'collection', v_coll, 'key', v_key, 'seq', v_rev, 'noop', false
            );
        end if;

        v_last_seq := greatest(v_last_seq, coalesce(v_rev, 0));
    end loop;

    -- 设备水位只用于对账展示，不参与任何“是否可推进”的判定。
    insert into public.devices(household_id, device_id, user_id, last_seen_at)
    values (v_household, v_device, auth.uid(), (extract(epoch from now()) * 1000)::bigint)
    on conflict (household_id, device_id) do update
        set last_seen_at = excluded.last_seen_at,
            user_id = excluded.user_id;

    return jsonb_build_object(
        'applied', v_applied,
        'conflicts', v_conflicts,
        'lastSeq', v_last_seq,
        'hasMore', false
    );
end;
$$;

-- ---------------------------------------------------------------------------
-- 7. 拉取：按 change_log.revision 游标分页（回执与 App 的 PullResponse 一致）
-- ---------------------------------------------------------------------------
-- p_exclude_device 非空时过滤掉该设备自己写的行；**扫描位置照常前进**，
-- 否则整页都是自己写的记录时水位不动，会永远卡在这批记录前面。
drop function if exists public.pull_changes(bigint, int);

create or replace function public.pull_changes(p_cursor bigint default 0,
                                               p_limit  int default 500,
                                               p_exclude_device text default '')
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_household uuid;
    v_limit     int;
    v_changes   jsonb;
    v_count     bigint;
    v_scanned   bigint;
    v_total     bigint;
begin
    if auth.uid() is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    v_household := public.current_household_id();
    if v_household is null then
        raise exception 'no household for current user' using errcode = '42501';
    end if;

    v_limit := least(greatest(coalesce(p_limit, 500), 1), 1000);

    select coalesce(max(c.revision), 0) into v_total
      from public.change_log c
     where c.household_id = v_household;

    with page as (
        select c.revision,
               c.collection,
               c.record_key,
               c.deleted,
               c.updated_by_user,
               c.updated_by_dev,
               c.updated_at
          from public.change_log c
         where c.household_id = v_household
           and c.revision > coalesce(p_cursor, 0)
         order by c.revision
         limit v_limit
    )
    select coalesce(
               jsonb_agg(
                   jsonb_build_object(
                       'collection', p.collection,
                       'key', p.record_key,
                       'payload', case when p.deleted then '{}'
                                       else coalesce(r.payload::text, '{}') end,
                       'seq', coalesce(r.revision, 0),
                       'deleted', p.deleted,
                       'updatedBy', p.updated_by_user,
                       'updatedAt', p.updated_at
                   ) order by p.revision
               ) filter (
                   where coalesce(p_exclude_device, '') = ''
                      or p.updated_by_dev <> p_exclude_device
               ),
               '[]'::jsonb
           ),
           count(*),
           coalesce(max(p.revision), coalesce(p_cursor, 0))
      into v_changes, v_count, v_scanned
      from page p
      left join public.records r
        on r.household_id = v_household
       and r.collection = p.collection
       and r.record_key = p.record_key;

    return jsonb_build_object(
        'changes', v_changes,
        'scannedSeq', v_scanned,
        'lastSeq', v_scanned,
        'hasMore', v_count >= v_limit,
        'seq', v_total,
        'resetRequired', coalesce(p_cursor, 0) > v_total
    );
end;
$$;

-- ---------------------------------------------------------------------------
-- 7.1 轻量元信息（对应 GET /v1/meta）
-- ---------------------------------------------------------------------------
create or replace function public.sync_meta(p_collections boolean default false)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_household uuid;
    v_seq       bigint;
    v_cols      jsonb;
begin
    if auth.uid() is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    v_household := public.current_household_id();
    if v_household is null then
        raise exception 'no household for current user' using errcode = '42501';
    end if;

    select coalesce(max(c.revision), 0) into v_seq
      from public.change_log c
     where c.household_id = v_household;

    if coalesce(p_collections, false) then
        select coalesce(jsonb_agg(x order by x->>'collection'), '[]'::jsonb)
          into v_cols
          from (
              select jsonb_build_object(
                         'collection', r.collection,
                         'count', count(*) filter (where not r.deleted),
                         'total', count(*),
                         'version', coalesce(max(r.revision), 0)
                     ) as x
                from public.records r
               where r.household_id = v_household
               group by r.collection
          ) t;
    else
        v_cols := '[]'::jsonb;
    end if;

    return jsonb_build_object(
        'instanceId', v_household::text,
        'seq', v_seq,
        'collections', v_cols
    );
end;
$$;

-- ---------------------------------------------------------------------------
-- 7.2 快照：按集合拉当前活数据（对应 GET /v1/snapshot）
-- ---------------------------------------------------------------------------
-- upTo 钉住一致点：revision > upTo 的行是快照期间别的设备写入的，留给后续增量拉取。
create or replace function public.sync_snapshot(p_collection text,
                                                p_cursor     bigint default 0,
                                                p_up_to      bigint default 0,
                                                p_limit      int    default 500)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_household uuid;
    v_limit     int;
    v_changes   jsonb;
    v_count     bigint;
    v_next      bigint;
begin
    if auth.uid() is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    v_household := public.current_household_id();
    if v_household is null then
        raise exception 'no household for current user' using errcode = '42501';
    end if;

    v_limit := least(greatest(coalesce(p_limit, 500), 1), 1000);

    with page as (
        select r.collection,
               r.record_key,
               r.payload::text as payload,
               r.revision,
               r.updated_by_user,
               r.updated_at
          from public.records r
         where r.household_id = v_household
           and r.collection = p_collection
           and not r.deleted
           and r.revision > coalesce(p_cursor, 0)
           and (coalesce(p_up_to, 0) <= 0 or r.revision <= p_up_to)
         order by r.revision
         limit v_limit
    )
    select coalesce(jsonb_agg(jsonb_build_object(
                       'collection', p.collection,
                       'key', p.record_key,
                       'payload', p.payload,
                       'seq', p.revision,
                       'deleted', false,
                       'updatedBy', p.updated_by_user,
                       'updatedAt', p.updated_at
                   ) order by p.revision), '[]'::jsonb),
           count(*),
           coalesce(max(p.revision), coalesce(p_cursor, 0))
      into v_changes, v_count, v_next
      from page p;

    return jsonb_build_object(
        'collection', p_collection,
        'upTo', coalesce(p_up_to, 0),
        'changes', v_changes,
        'nextCursor', v_next,
        'done', v_count < v_limit
    );
end;
$$;

-- ---------------------------------------------------------------------------
-- 7.3 对账统计（对应 GET /v1/stats）
-- ---------------------------------------------------------------------------
create or replace function public.sync_stats()
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_household uuid;
    v_seq       bigint;
    v_cols      jsonb;
    v_devices   jsonb;
    v_conflicts bigint;
begin
    if auth.uid() is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    v_household := public.current_household_id();
    if v_household is null then
        raise exception 'no household for current user' using errcode = '42501';
    end if;

    select coalesce(max(c.revision), 0) into v_seq
      from public.change_log c
     where c.household_id = v_household;

    select coalesce(jsonb_agg(x order by x->>'collection'), '[]'::jsonb)
      into v_cols
      from (
          select jsonb_build_object(
                     'collection', r.collection,
                     'live', count(*) filter (where not r.deleted),
                     'deleted', count(*) filter (where r.deleted),
                     'total', count(*),
                     'lastSeq', coalesce(max(r.revision), 0)
                 ) as x
            from public.records r
           where r.household_id = v_household
           group by r.collection
      ) t;

    select coalesce(jsonb_agg(jsonb_build_object(
                       'device_id', d.device_id,
                       'user_id', coalesce(d.user_id::text, ''),
                       'name', d.name,
                       'last_seen_at', d.last_seen_at,
                       'last_pull_seq', d.last_pull_revision
                   ) order by d.last_seen_at desc), '[]'::jsonb)
      into v_devices
      from public.devices d
     where d.household_id = v_household;

    select count(*) into v_conflicts
      from public.conflicts c
     where c.household_id = v_household;

    return jsonb_build_object(
        'seq', v_seq,
        'collections', v_cols,
        'devices', v_devices,
        'conflictCount', v_conflicts
    );
end;
$$;

-- ---------------------------------------------------------------------------
-- 8. RLS：家内成员可读；写只走上面的 security definer 函数
-- ---------------------------------------------------------------------------
alter table public.households        enable row level security;
alter table public.household_members enable row level security;
alter table public.records           enable row level security;
alter table public.household_settings enable row level security;
alter table public.change_log        enable row level security;
alter table public.devices           enable row level security;
alter table public.conflicts         enable row level security;

drop policy if exists households_read on public.households;
create policy households_read on public.households
    for select to authenticated
    using (public.is_household_member(id));

drop policy if exists household_members_read on public.household_members;
create policy household_members_read on public.household_members
    for select to authenticated
    using (public.is_household_member(household_id));

drop policy if exists records_read on public.records;
create policy records_read on public.records
    for select to authenticated
    using (public.is_household_member(household_id));

drop policy if exists settings_read on public.household_settings;
create policy settings_read on public.household_settings
    for select to authenticated
    using (public.is_household_member(household_id));

drop policy if exists change_log_read on public.change_log;
create policy change_log_read on public.change_log
    for select to authenticated
    using (public.is_household_member(household_id));

drop policy if exists devices_read on public.devices;
create policy devices_read on public.devices
    for select to authenticated
    using (public.is_household_member(household_id));

drop policy if exists conflicts_read on public.conflicts;
create policy conflicts_read on public.conflicts
    for select to authenticated
    using (public.is_household_member(household_id));

-- 只允许登录用户调用同步函数；禁止匿名调用。
revoke all on function public.current_household_id() from public, anon;
revoke all on function public.apply_changes(jsonb) from public, anon;
revoke all on function public.pull_changes(bigint, int, text) from public, anon;
revoke all on function public.sync_meta(boolean) from public, anon;
revoke all on function public.sync_snapshot(text, bigint, bigint, int) from public, anon;
revoke all on function public.sync_stats() from public, anon;

grant execute on function public.current_household_id() to authenticated;
grant execute on function public.apply_changes(jsonb) to authenticated;
grant execute on function public.pull_changes(bigint, int, text) to authenticated;
grant execute on function public.sync_meta(boolean) to authenticated;
grant execute on function public.sync_snapshot(text, bigint, bigint, int) to authenticated;
grant execute on function public.sync_stats() to authenticated;

-- ---------------------------------------------------------------------------
-- 9. 首次建家（在 Dashboard 建好两个 Auth 用户后执行）
-- ---------------------------------------------------------------------------
-- 账号不在 SQL 里硬编码密码：Supabase Dashboard → Authentication → Users 创建
-- 「时天」「柠檬」两个邮箱账号，并关闭公开注册。
--
-- 绑定两者的 SQL 见同目录 0002_bind_family.sql（含确认邮箱与 household_members 映射）。
