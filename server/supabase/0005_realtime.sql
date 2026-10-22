-- 柠檬百宝箱 · 开启 records 的实时订阅
-- 在 Supabase SQL Editor 中执行；本文件幂等，可重复执行。
--
-- 只把 public.records 加入 Realtime 的 publication：
-- 客户端在前台订阅「有人改了数据」这一信号，收到后仍走原有的增量同步落地，
-- 不在这里直传行内容（避免出现第二条写路径）。
--
-- 安全前提已经具备：records 已启用 RLS，且 authenticated 只有
-- 「本家庭成员可读」的 SELECT 策略，Realtime 推送同样按订阅者身份判定。

do $$
begin
    if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
        create publication supabase_realtime;
    end if;

    if not exists (
        select 1
          from pg_publication_tables
         where pubname = 'supabase_realtime'
           and schemaname = 'public'
           and tablename = 'records'
    ) then
        alter publication supabase_realtime add table public.records;
    end if;
end
$$;
