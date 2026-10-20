-- 修复旧 Web 版本创建的家当记录。
--
-- 背景：旧版 server/web/index.html 的 blank(COLL.ITEMS) 没有写 deletedAt，
-- 而 Android 的 ItemSnapshot 当时把 deletedAt 当作必填字段，导致
-- syncRecordsToPayload 用 MissingFieldException 静默跳过这些记录。
-- 新版本已给 deletedAt 默认值，能直接解码；本脚本把服务端历史载荷也补齐，
-- 让旧记录在「重建 / 重置水位」后能正常落地。
--
-- 线上 D1：
--   npx wrangler d1 execute DB --remote --file server/fix-legacy-items.sql
--
-- 本地预览库：
--   npx wrangler d1 execute DB --local --file server/fix-legacy-items.sql
--
-- 执行后，曾经拉过这批记录的设备需要一次「以服务端为准重建」，或把
-- sync_state 里的 PULL_WATERMARK 归零；否则旧水位已经越过它们，不会自动重拉。

UPDATE records
SET payload = json_set(payload, '$.deletedAt', NULL)
WHERE collection = 'items'
  AND json_valid(payload)
  AND json_extract(payload, '$.deletedAt') IS NULL;
