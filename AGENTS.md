# LemonBox 工程规则

本文件是给未来开发者 / AI 代理的硬约束。动手前先读，改完按 `.github/engineering-checklist.md` 自查。

## 构建与验证

- 编译：`./gradlew :app:compileDebugKotlin --offline`
- 单测：`./gradlew :app:testDebugUnitTest --offline`
- 涉及 UI：在 `lemonbox` AVD 上安装 debug APK，用 `uiautomator dump` 核对文案与状态（方法见项目记忆 `android-emulator-workflow`）。
- 未通过编译与单测，不要声称完成。

## 数据安全（必须遵守）

1. **水位只在数据成功落地后推进**；分页截断/异常/取消时不得推进，也不得把快照标记为完成。
2. **坏载荷可以跳过，但必须计数并让用户看见**；不能静默丢弃。
3. **整块偏好同步必须有 `updatedAt` 保护**：服务端版本不旧于本机才落地。
4. **墓碑清理必须带「服务端已确认」前提**（`TOMBSTONE_CONFIRMED_SQL`）。
5. **外部 ZIP 按不可信输入处理**：解压必须防 Zip Slip。
6. **schema 变更必须补连续 Migration**；禁止 `fallbackToDestructiveMigration()`。
7. **登录态是云同步的唯一开关**：不要新增「图片上传 / 自动上传 / 后台补传 / 多设备同步」这类可由登录推导的用户开关。
8. **后台任务必须唯一命名 + 明确 KEEP/REPLACE**，登录/退出时重排。

## 同步架构前提（刻意设计，不要改）

- 服务端 `records` 不做 `user_id` 隔离：整个部署就是一份家庭数据，多账号共享是设计目标。
- Web 端通过拉取同步历史/全量重建获取数据；App 是本地 Room + 增量水位。两套架构并存是刻意的。
- 不要建议给 `records` 加 `user_id`，也不要把 Web 改成 App 式持久化增量水位。

## 代码风格

- 注释解释「为什么」，不重复「做了什么」。
- 用户可见文案用中文，简洁、说清去处与后果。
- 新增依赖前先确认现有依赖能否完成。

详细检查清单与复盘模板见 `.github/engineering-checklist.md`，本次审计报告见 `.github/audit-2026-10-21.md`。
