-- 柠檬百宝箱 · 设备拉取进度上报
-- 在 Supabase SQL Editor 中执行；本文件幂等，可重复执行。
--
-- 背景：旧 Worker 在 /v1/pull 的最后一页顺手把 devices.last_pull_revision 写掉；
-- Supabase 的 pull_changes 是 stable 函数（不能落库），于是诊断页每台设备都显示
-- 「已同步到序号 0」，看起来像同步没生效。这里补一条独立的写入通道：
-- 由客户端在**整轮拉取成功**后主动上报一次，只用于诊断展示。
--
-- 为什么钳制到服务端当前最大序号：客户端报上来的只是它自己的游标，
-- 万一是旧库/换库前的编号，直接写进来会让诊断页显示一个根本不存在的进度。

create or replace function public.report_pull_progress(p_device_id   text,
                                                      p_seq         bigint,
                                                      p_device_name text default '')
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_household uuid;
    v_seq       bigint;
begin
    if auth.uid() is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    if coalesce(p_device_id, '') = '' then
        return;
    end if;

    v_household := public.current_household_id();
    if v_household is null then
        raise exception 'no household for current user' using errcode = '42501';
    end if;

    select coalesce(max(c.revision), 0) into v_seq
      from public.change_log c
     where c.household_id = v_household;

    v_seq := least(greatest(coalesce(p_seq, 0), 0), v_seq);

    insert into public.devices(household_id, device_id, user_id, name, last_seen_at, last_pull_revision)
    values (v_household, p_device_id, auth.uid(), coalesce(p_device_name, ''),
            (extract(epoch from now()) * 1000)::bigint, v_seq)
    on conflict (household_id, device_id) do update
        set last_pull_revision = greatest(devices.last_pull_revision, excluded.last_pull_revision),
            last_seen_at      = greatest(devices.last_seen_at, excluded.last_seen_at),
            user_id           = excluded.user_id,
            -- 设备名只补空，不覆盖：换机型/改名不该把历史那一行抹成空串。
            name              = coalesce(nullif(excluded.name, ''), devices.name);
end;
$$;

revoke all on function public.report_pull_progress(text, bigint, text) from public, anon;
grant execute on function public.report_pull_progress(text, bigint, text) to authenticated;
