-- 柠檬百宝箱 · 修复「墓碑载荷被丢成 {}」导致删除无法落地
-- 在 Supabase SQL Editor 中执行；本文件幂等，可重复执行。
--
-- 现象：
--   同步诊断页显示「有 N 条服务端记录无法解析，已跳过」，且数量接近历史删除总数；
--   已被删除的位置/物品在设备同步后重新出现（服务端 revision 被设备用本地旧行覆盖回来）。
--
-- 根因：
--   pull_changes 为墓碑行返回 `'{}'`（0001 里的 `case when p.deleted then '{}'`），
--   而客户端是按载荷里的 deletedAt/purgedAt 应用删除的：
--     1) `{}` 解不成任何 Snapshot，于是每条墓碑都被计入「无法解析」；
--     2) 删除从来没有真正落到设备上；
--     3) 落地记账时 `payloadUpdatedAt('{}') = 0` 会让客户端判定「本机更旧?不，本机更新」，
--        保留脏位并把本地这份「还活着」的行重新推回服务端——墓碑被原地复活。
--
-- 修复：
--   records.payload 一直保存着删除那一刻的完整载荷（含 deletedAt/purgedAt），
--   这里直接原样返回，不再因为 deleted 标记而丢弃内容。客户端据此按正常合并通道
--   应用删除，`payloadUpdatedAt` 也拿得到真实时间，不会再误判为「本机更新」。

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
                       -- 墓碑也返回原载荷：删除要靠里面的 deletedAt/purgedAt 才能落地。
                       'payload', coalesce(r.payload::text, '{}'),
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
