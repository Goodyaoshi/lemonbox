-- 柠檬百宝箱 · Supabase 第二步：确认两个账号 + 绑定同一个家
-- 在 SQL Editor 里整段执行；本文件幂等，可重复执行。
--
-- 为什么需要这一步：
--   Dashboard 手动创建的账号默认 email_confirmed_at 为空，密码登录会直接被拒
--   （400 email_not_confirmed），必须在库里确认一次；关闭注册不等于确认邮箱。

-- 1) 确认两个账号（只补空值，不覆盖已有时间）
update auth.users
   set email_confirmed_at = coalesce(email_confirmed_at, now()),
       updated_at = now()
 where email in ('shitian@lemonbox.app', 'ningmeng@lemonbox.app');

-- 2) 建家：只建一次
insert into public.households(name)
select '我们的家'
where not exists (select 1 from public.households);

-- 3) 把「时天」「柠檬」都挂进这个家
--    邮箱 → 身份/称呼的映射只在这里定义一次，App 端按同一份映射登录。
insert into public.household_members(household_id, user_id, role, display_name)
select h.id,
       u.id,
       case when u.email = 'shitian@lemonbox.app' then 'owner' else 'member' end,
       case when u.email = 'shitian@lemonbox.app' then '时天'
            when u.email = 'ningmeng@lemonbox.app' then '柠檬'
       end
  from (select id from public.households order by created_at limit 1) h
  join auth.users u
    on u.email in ('shitian@lemonbox.app', 'ningmeng@lemonbox.app')
on conflict (household_id, user_id) do update
   set display_name = excluded.display_name;

-- 4) 自检：应返回 2 行，且 confirmed 全为 true
select u.email,
       m.role,
       m.display_name,
       (u.email_confirmed_at is not null) as confirmed
  from public.household_members m
  join auth.users u on u.id = m.user_id
 order by m.role desc, u.email;
