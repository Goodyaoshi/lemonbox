# 工程检查清单

> 目的：把「数据会不会丢 / 多设备会不会不一致 / 升级会不会崩」这类**编译器看不出来**的问题，
> 变成提交前必须逐条回答的检查项。新发现的问题先补进本清单，再修代码。

## 一、同步（最高风险）

### 1. 水位只在数据成功落地后推进
- `PULL_WATERMARK` 必须在 `importBackup` 成功之后写，且与落地处于同一段收尾逻辑。
- 分页截断、异常、取消时**不得**推进水位，也不得把 `SNAPSHOT_DONE` 置 1。
- 快照按集合分页；任一集合未拉完，整次快照都不算完成，下一轮重来（重复合并是幂等的）。
- 反例：`pullSnapshot` 曾在 `truncated=true` 时仍写 `SNAPSHOT_DONE=1` 并把水位钉到 `meta.seq`，
  导致截断点之前的记录永久漏拉。

### 2. 坏载荷必须可见
- 解析失败的记录可以跳过（不能卡死整轮同步），但必须计数并持久化。
- 计数要出现在：云同步页、数据中心入口、同步诊断页。
- 反例：`syncRecordsToPayload` 用 `runCatching{}.getOrNull()` 静默丢弃，水位照常推进。

### 3. 整块偏好要有 updatedAt 保护
- `PreferencesSnapshot.updatedAt` 必须写入；拉取落地时，服务端版本不旧于本机才应用。
- 本地备份恢复（`markDirty=true`）例外，按用户主动恢复处理。
- 反例：`pull` 先于 `push`，服务端旧偏好会把本机刚改的设置覆盖掉，然后本机再推旧值。

### 4. 墓碑清理必须有「服务端已确认」前提
- 软删墓碑 30 天、永久删除墓碑 180 天，SQL 末尾必须带 `TOMBSTONE_CONFIRMED_SQL`。
- 新增同步表时，同时补：墓碑清理 SQL、`SyncTables` 映射、迁移、`SyncRecordsTest` 的集合清单。

### 5. 重建 / 撤销必须可回退
- 重建前导出本机全量到 `filesDir`（不是 `cacheDir`），失败回执要带撤销路径。
- 重建后不得把本机偏好反向推回服务端；先写入当前偏好签名，等快照从服务端拉回。
- 撤销导入必须 `markDirty=true`，否则未上过服务端的记录永远推不上去。

## 二、数据库与备份

### 6. 迁移链连续，禁止 destructive fallback
- 每次 `version+1` 必须有对应 `Migration(n, n+1)` 并加入 `Migrations.ALL`。
- 不允许 `fallbackToDestructiveMigration()`：升级失败会清空用户全部数据。
- 改表后跑 `DaoUpdateSqlAuditTest` / `TombstonePurgeSqlTest`。

### 7. 外部 ZIP 一律按不可信输入处理
- 解压必须校验 canonical path 在目标目录内（防 Zip Slip）。
- 校验和只防损坏，不是签名；不要把它当来源可信证明。
- 图片恢复只接受 `images/*.jpg`，按文件名重建，不信任清单里的路径。

## 三、后台任务与生命周期

### 8. 每个 WorkManager 任务都有唯一名字与明确策略
- 周期任务用 `enqueueUniquePeriodicWork`；需要重排时用 `CANCEL_AND_REENQUEUE`，
  仅「已排上就保留」才用 `KEEP`。
- 登录/退出、开关变化必须重排或撤销相关任务。
- Worker 内先判 `readiness`，未登录/未配置直接 `Result.success()`，不要反复重试。

### 9. 前台/后台状态必须一致
- 同一业务条件（登录、开关、网络）在 `LemonApplication`、ViewModel、Worker 三处使用同一判断。
- 新增同步开关前先问：它能否由登录态推导？能则不要新增用户开关。

## 四、发布

### 10. release 包必须单独验证
- R8 开启时，kotlinx.serialization、ONNX、ZXing、Hilt 的 keep 规则必须保留。
- 更新包必须校验 SHA-256 与签名；只校验其中一个不够。
- 每次发布前在真机/模拟器安装 debug 与 release 各一次，至少走一遍登录、同步、备份导入。

## 五、提交前检查清单

- [ ] `./gradlew :app:compileDebugKotlin` 通过
- [ ] `./gradlew :app:testDebugUnitTest` 通过
- [ ] 新增/修改的同步逻辑有对应单测（至少覆盖失败路径）
- [ ] 涉及 UI 的改动在 `lemonbox` AVD 上安装并核对关键文案/状态
- [ ] 涉及 schema 的改动：迁移链连续、无 destructive fallback
- [ ] 涉及外部文件：路径校验、大小上限、失败可见
- [ ] 涉及后台任务：唯一名字、策略、登录态重排
- [ ] `git diff --check` 无空白错误；diff 只含预期文件

## 六、缺陷复盘模板

```markdown
### 现象
用户看到什么？在什么操作后出现？

### 根因
哪个假设错了？水位 / 脏位 / 时序 / 解析 / 迁移 / 权限？

### 数据影响
是否丢数据？影响范围？能否恢复？需要用户做什么？

### 修复
改了哪些文件？为什么这样改不会引入新的不一致？

### 防回归
新增了哪个测试？清单里补了哪一条？CI 能否拦住？
```

## 七、评审时优先问的五个问题

1. 这一步失败时，水位/脏位/游标会不会被错误推进？
2. 这段逻辑在「另一台设备同时修改」时会发生什么？
3. 坏数据、空数据、超大数据的路径是否可见、可恢复？
4. 登录、退出、换账号、断网、进程被杀之后，状态是否仍然自洽？
5. 这个改动只修了 UI，还是也修了产生错误状态的那一层？
