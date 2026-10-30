-- 柠檬百宝箱 · Supabase MCP 写入口
-- 在 Supabase SQL Editor 中执行；本文件幂等，可重复执行。
--
-- 背景：MCP 的写入必须落进 Supabase 的 revision / change_log 体系，
-- 否则 App 的增量拉取与 Realtime 都看不到 AI 的修改。这里把 MCP 的写路径
-- 接到与客户端推送同一条发号路径上：MCP 的每一次写入都像一次正常的客户端推送，
-- App 下一次增量拉取与 Realtime 都能看到。
--
-- 权限边界：
--   * 所有 mcp_* 函数只授权给 service_role（Edge Function 持有 service key）。
--   * authenticated/anon 一律禁止执行，避免浏览器端绕过 RLS 直接写。
--   * 写入核心与 apply_changes 共用同一段逻辑，不复制两份发号规则。

-- ---------------------------------------------------------------------------
-- 1. 写入核心：不依赖 auth.uid()，按显式 household_id 工作
-- ---------------------------------------------------------------------------
create or replace function public._apply_changes_core(p_household uuid,
                                                     p_device    text,
                                                     p_actor     text,
                                                     p_user      uuid,
                                                     p_changes   jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
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
    if p_household is null then
        raise exception 'household_id is required' using errcode = '22023';
    end if;

    for v_change in select * from jsonb_array_elements(p_changes)
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
        where r.household_id = p_household
          and r.collection = v_coll
          and r.record_key = v_key
        for update;

        v_exists := found;

        if v_exists and v_base <> v_current.revision and not v_force then
            -- 版本落后：记台账并回执服务端当前版本，由调用方决定重推还是落地。
            insert into public.conflicts(household_id, collection, record_key,
                                         base_revision, server_revision, by_device)
            values (p_household, v_coll, v_key, v_base, v_current.revision, p_device);
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
                       updated_by_user = p_actor,
                       updated_by_dev = p_device,
                       updated_at = v_updated_at,
                       deleted = v_deleted
                 where r.household_id = p_household
                   and r.collection = v_coll
                   and r.record_key = v_key;
            else
                insert into public.records(household_id, collection, record_key, payload,
                                           revision, updated_by_user, updated_by_dev,
                                           updated_at, deleted)
                values (p_household, v_coll, v_key, v_payload, v_rev, p_actor, p_device,
                        v_updated_at, v_deleted);
            end if;

            insert into public.change_log(revision, household_id, collection, record_key,
                                          deleted, updated_by_user, updated_by_dev, updated_at)
            values (v_rev, p_household, v_coll, v_key, v_deleted, p_actor, p_device, v_updated_at);

            v_applied := v_applied || jsonb_build_object(
                'collection', v_coll, 'key', v_key, 'seq', v_rev, 'noop', false
            );
        end if;

        v_last_seq := greatest(v_last_seq, coalesce(v_rev, 0));
    end loop;

    -- 设备水位只用于对账展示，不参与任何“是否可推进”的判定。
    -- p_user 为 null 时（MCP）不覆盖已有真人账号归属。
    insert into public.devices(household_id, device_id, user_id, last_seen_at)
    values (p_household, p_device, p_user, (extract(epoch from now()) * 1000)::bigint)
    on conflict (household_id, device_id) do update
        set last_seen_at = excluded.last_seen_at,
            user_id = coalesce(excluded.user_id, devices.user_id);

    return jsonb_build_object(
        'applied', v_applied,
        'conflicts', v_conflicts,
        'lastSeq', v_last_seq,
        'hasMore', false
    );
end;
$$;

-- ---------------------------------------------------------------------------
-- 2. 原 apply_changes 只做身份校验，写入交给核心
-- ---------------------------------------------------------------------------
create or replace function public.apply_changes(p_batch jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_household uuid;
begin
    if auth.uid() is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    v_household := (p_batch->>'household_id')::uuid;
    if not public.is_household_member(v_household) then
        raise exception 'not a member of household %', v_household using errcode = '42501';
    end if;

    if jsonb_typeof(p_batch->'changes') <> 'array' then
        raise exception 'changes must be an array';
    end if;

    return public._apply_changes_core(
        v_household,
        coalesce(p_batch->>'device_id', ''),
        coalesce(p_batch->>'actor', ''),
        auth.uid(),
        p_batch->'changes'
    );
end;
$$;

-- ---------------------------------------------------------------------------
-- 3. MCP 写入入口：只有 service_role 可执行
-- ---------------------------------------------------------------------------
create or replace function public.mcp_apply_changes(p_household uuid, p_changes jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
begin
    if p_household is null then
        raise exception 'household_id is required' using errcode = '22023';
    end if;
    if not exists (select 1 from public.households h where h.id = p_household) then
        raise exception 'household % not found', p_household using errcode = '22023';
    end if;
    if jsonb_typeof(p_changes) <> 'array' then
        raise exception 'changes must be an array';
    end if;

    -- 与客户端写入共用 category/device 语义：AI 的每次写入都算一次真实修改，
    -- App 端 LWW 才会接受（updatedAt 由 Edge Function 写入当前毫秒）。
    return public._apply_changes_core(p_household, 'mcp', 'AI助手', null, p_changes);
end;
$$;

-- ---------------------------------------------------------------------------
-- 4. MCP 对账读取：按显式 household_id 统计（不依赖 auth.uid()）
-- ---------------------------------------------------------------------------
create or replace function public.mcp_sync_status(p_household uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_seq       bigint;
    v_cols      jsonb;
    v_devices   jsonb;
    v_conflicts bigint;
begin
    if p_household is null then
        raise exception 'household_id is required' using errcode = '22023';
    end if;

    select coalesce(max(c.revision), 0) into v_seq
      from public.change_log c
     where c.household_id = p_household;

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
           where r.household_id = p_household
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
     where d.household_id = p_household;

    select count(*) into v_conflicts
      from public.conflicts c
     where c.household_id = p_household;

    return jsonb_build_object(
        'seq', v_seq,
        'collections', v_cols,
        'devices', v_devices,
        'conflictCount', v_conflicts,
        'backend', 'supabase'
    );
end;
$$;

-- ---------------------------------------------------------------------------
-- 5. 权限：MCP 函数只给 service_role；客户端函数恢复原有授权
-- ---------------------------------------------------------------------------
revoke all on function public._apply_changes_core(uuid, text, text, uuid, jsonb)
    from public, anon, authenticated;
revoke all on function public.mcp_apply_changes(uuid, jsonb) from public, anon, authenticated;
revoke all on function public.mcp_sync_status(uuid) from public, anon, authenticated;

grant execute on function public._apply_changes_core(uuid, text, text, uuid, jsonb) to service_role;
grant execute on function public.mcp_apply_changes(uuid, jsonb) to service_role;
grant execute on function public.mcp_sync_status(uuid) to service_role;

revoke all on function public.apply_changes(jsonb) from public, anon;
grant execute on function public.apply_changes(jsonb) to authenticated;

-- 6. 自检
-- select public.mcp_sync_status((select id from public.households order by created_at limit 1));
