# 柠檬百宝箱 LemonBox

<p align="center">
  <a href="#"><img src="https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white" alt="Android" /></a>
  <a href="#"><img src="https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" /></a>
  <a href="#"><img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" /></a>
  <a href="#"><img src="https://img.shields.io/badge/minSdk-26-FF8A00" alt="minSdk 26" /></a>
  <a href="./LICENSE"><img src="https://img.shields.io/badge/license-MIT-22c55e" alt="License MIT" /></a>
</p>

> 把家里的东西管明白。

柠檬百宝箱是一款面向家庭的 Android 物品管理 App，用来记录家中物品的位置、数量、单位、有效期与状态，降低遗忘和浪费成本。在「家当管理」之上，它还能根据家里现有的食材自动搭配菜谱、生成每周菜单，缺什么补什么。

> 数据默认**只保存在手机本机**，不依赖 Google、百度、腾讯、OpenAI 等任何厂商云服务。局域网同步直接在家内网完成，不经任何服务器。
>
> 多设备之间有三种途径：
> ① 局域网同步——同一 WiFi 下两台设备直连，不经任何服务器；
> ② 云端账号同步——登录后由自建的轻量同步服务端（Cloudflare Worker + D1）做权威中枢，
> 物品 / 记账 / 纪念日等文字数据按序号增量推拉，各设备自动收敛到同一状态；
> ③ 图片云端存储——把你自己的 S3 兼容对象存储（如 Filebase）当作图片仓库，多设备共享、节省手机空间。
>
> 云端能力**都可关、可不配**：**不登录就是纯本地**，数据只留在本机。
> 对象存储的地址与访问密钥**只存在服务端**，客户端每次只换取几分钟有效的预签名地址；
> 安装包里不含任何密钥，只带一个公开的服务端域名。文字数据以明文 JSON 推送，
> 安全性取决于服务端与存储桶本身（请使用私有桶与最小权限凭据）。

---

## 功能特点

**家当与物品**

- 📸 **拍照 / 条码快速录入**：从拍照、扫码到保存尽量压缩操作路径，适合高频、碎片化记录。
- 🏠 **首页一览**：搜索框输入即筛选，今天的事、纪念日、今天吃什么、本月账一屏掌握。
- 🗂 **分类与位置双维度管理**：分类带图标、支持多级结构（内置受保护的食品 / 零食等分类树），存放位置独立管理，互不干扰。
- 📦 **家当状态体系**：使用状态（未使用 / 使用中 / 已用完）、处置状态（在库 / 已借出 / 已送人 / 已丢弃）与补货标记三个正交维度；物品支持数量与单位（3 瓶、2 箱），侧滑即可「用 1 件」或「用完」。
- 🛒 **待买清单**：手动添加或由菜谱缺食材自动加入，带单位与分类关联，可按分类筛选。
- 🔮 **补货预测**：按消耗速度估算在用物品的可用天数，提前提示该补了。
- ♻️ **回收站**：删除后 30 天内可恢复，超期自动清理。

**菜谱与吃饭**

- 🍳 **菜谱与每周菜单**：内置家常菜谱库（主食 / 荤菜 / 素菜 / 汤饮 TAG 筛选），按现有食材自动搭配每周菜单，保证主食、蛋白、蔬菜齐全；点「做了」自动扣减对应食材，缺的食材一键进入待买清单。

**记账**

- 💰 **记账与预算**：支出 / 收入 / 转账三类流水，支持分类、账户、资产负债、月度预算与超支提醒；月起始日可自定义（不限于自然月）。
- 🔁 **记账模板与周期记账**：常用账单存为模板一键套用；房租工资等按天 / 周 / 月 / 年自动生成账单。

**提醒与时间**

- ⏰ **到期提醒**：提醒阶梯（1/3/7/14/30/60/90 天）支持全局默认 + 按物品覆盖，每日固定时段（默认 08:00 / 19:00）合并通知，同一物品同一天只提醒一次。
- ✅ **待办提醒**：解冻、备菜、家务等一次性与周期待办，精确闹钟 + 常驻保活防漏提醒。
- 🎂 **纪念日**：在一起的累计、生日（支持农历）与倒数日，可提前提醒。
- 🕰 **时间胶囊**：写段话给未来的自己，到设定那天才能打开。

**其它**

- 🧳 **打包清单**：出行前列好要带的东西，出发前照着勾。
- 🌙 **睡前氛围**：夜里的一处安静角落，附就寝提醒。
- 🔎 **OCR 识别录入**：离线 OCR（PP-OCRv6）拍照取字，全程在本机完成、不上传图片。
- 📶 **局域网同步**：手机之间在同一 WiFi 下直连同步，逐条可勾选、可撤销（按 syncId 对齐、updatedAt 解决冲突），换机不丢数据。
- ☁️ **多设备云同步**：登录云端账号后，文字数据按序号增量推到自建服务端（Cloudflare Worker + D1），
  各设备拉到他人变更后各自合并，谁都不覆盖谁；不登录就是纯本地。
- 🖼 **图片云端存储**：图片上传到你自己的 S3 兼容对象存储，多设备共享、节省手机空间；
  密钥只在服务端，客户端凭登录态换取预签名地址。
- 🔑 **云端账号**：账号密码由服务端预先配置，客户端只能登录、不内置任何账号；会话令牌由
  Android Keystore（AES-256-GCM）加密存本机。
- 🔒 **应用锁**：可设 PIN，进入 App 需验证。
- 🧩 **桌面小组件 / 快捷方式**：不解锁就能看到待买清单、今天的事和本月支出。
- ✨ **沉浸式 UI**：柠檬系配色、大圆角卡片、毛玻璃悬浮导航、沉浸式顶部布局，支持浅色 / 深色 / 跟随系统。

---

## 技术栈

- Kotlin + Coroutines / Flow
- Jetpack Compose（Material 3）+ Navigation Compose
- Room（含 schema 导出与版本迁移）
- Hilt
- WorkManager（到期提醒、云同步、后台维护）
- CameraX（拍照录入）
- ZXingLite（条码扫描）
- Coil（图片加载，缓存键固定为内容哈希）
- kotlinx.serialization（备份与菜谱序列化）
- Haze（毛玻璃效果）
- NanoHTTPD（局域网同步服务）
- ONNX Runtime + PP-OCRv6（离线文字识别）
- tyme4kt（农历换算）
- 自实现 SigV4（S3 兼容对象存储预签名，由服务端签发，无 AWS SDK 依赖）

**同步服务端**（独立于 App，源码在 `server/`）

- Cloudflare Worker（JavaScript，零依赖）
- Cloudflare D1（SQLite，权威序号 `server_seq` 单调递增）
- 全部密钥以 Worker Secret 保存；客户端只拿到登录令牌与几分钟有效的预签名 URL

---

## 项目结构

```text
.
├─ app/
│  ├─ src/main/java/com/goodyaoshi/lemonbox
│  │  ├─ data/
│  │  │  ├─ local/          # Room 实体、DAO、数据库与迁移
│  │  │  ├─ backup/         # 快照载荷、合并策略与导入导出（局域网同步与云同步共用）
│  │  │  ├─ imagecloud/     # S3 客户端、图片上传与回填缓存（密钥在服务端）
│  │  │  ├─ remote/         # 云端账号：会话存储、登录/推拉客户端（Cloudflare Worker）
│  │  │  ├─ sync/           # 局域网同步 + 多设备云同步（快照推拉）
│  │  │  ├─ meal/           # 菜谱、一餐搭配与每周菜单
│  │  │  ├─ repository/     # 数据仓库层
│  │  │  ├─ search/         # 本地搜索解析与过滤
│  │  │  ├─ settings/       # 应用设置（SharedPreferences + StateFlow）
│  │  │  ├─ ocr/            # 离线 OCR 模型管理与文字解析
│  │  │  ├─ export/         # 可读报告导出
│  │  │  ├─ forecast/       # 补货预测
│  │  │  ├─ update/         # 应用内更新检查
│  │  │  └─ widget/         # 桌面小组件数据
│  │  ├─ di/                # Hilt 依赖注入
│  │  ├─ ui/
│  │  │  ├─ components/     # 通用 UI 组件
│  │  │  ├─ navigation/     # 路由与导航
│  │  │  ├─ scan/           # 条码扫描
│  │  │  ├─ screen/         # 页面（home / recipe / category / detail / settings 等）
│  │  │  └─ viewmodel/      # 状态与交互逻辑
│  │  ├─ util/              # 通知、日期、图片等工具
│  │  ├─ widget/            # 桌面小组件
│  │  ├─ LemonApplication.kt
│  │  └─ MainActivity.kt
│  └─ src/main/res/         # 图标、主题、字符串、图片资源
├─ app/schemas/             # Room 数据库 schema 导出（当前 DB 版本 36；历史版本 1-9 未导出）
└─ server/                  # 同步服务端（Cloudflare Worker + D1 schema + 部署说明）
```

架构采用 MVVM：

```text
UI -> ViewModel -> Repository -> Room DB
```

---

## 安装方法

### 环境要求

- Android Studio 最新稳定版
- JDK 21
- Android SDK 35（minSdk 26）

### 克隆项目

```bash
git clone git@github.com:Goodyaoshi/lemonbox.git
cd lemonbox
```

> 项目已在 `settings.gradle.kts` 中配置阿里云镜像源，国内网络环境可直接同步依赖。

### 同步与构建

```bash
./gradlew assembleDebug
```

Windows 可使用：

```powershell
.\gradlew.bat assembleDebug
```

运行单元测试：

```powershell
.\gradlew.bat test
```

然后使用 Android Studio 运行 `app` 模块，或将生成的 APK 安装到设备：

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

首次启动建议重点体验以下路径：

1. 首页 -> 搜索框直接筛选 / 即将过期 / 未来一周菜谱
2. 底部中间拍照按钮 -> 快速录入 -> 保存
3. 吃饭 -> TAG 筛选 -> 加入每周菜单 -> 点「做了」看食材自动扣减
4. 记账 -> 记一笔 / 统计 / 预算 / 计划（模板与周期记账）
5. 我的 -> 提醒与纪念日 / 小工具（时间胶囊、打包清单、睡前氛围）/ 设置 / 数据中心

---

## 配置说明

本项目以本地功能为主，**不依赖任何第三方应用云**；云同步使用**自建的服务端**
（Cloudflare Worker + D1，源码在 `server/`），图片走自备的 S3 兼容对象存储，
应用内更新走 GitHub Release。云端能力可关、可不配，**不登录即为纯本地**。关键配置如下：

| 配置项 | 位置 | 说明 |
| --- | --- | --- |
| `applicationId` | `app/build.gradle.kts` | `com.goodyaoshi.lemonbox` |
| `minSdk` / `targetSdk` | `app/build.gradle.kts` | `26 / 35` |
| 本地数据库 | Room（`lemonbox.db`） | 分类 / 位置含受保护种子数据，schema 随版本迁移 |
| 应用设置 | SharedPreferences | 提醒阶梯、提醒时段、主题模式、每周菜单等 |
| 通知权限 | Android 13+ | 到期提醒依赖 `POST_NOTIFICATIONS` |
| 相机权限 | 首次拍照时申请 | 拍照录入依赖 CameraX |
| 精确闹钟权限 | Android 12+ | 待办提醒依赖 `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM`，不可用时退化为窗口闹钟 |
| 局域网权限 | 本地网络 | 同步仅访问内网，cleartext 限定本地网络 |
| 云端账号 | 服务端预先配置 | 账号密码只存在服务端，客户端不内置、不能自注册；**未登录时云端能力全部不动作** |
| 多设备数据同步 | 可选，默认开启 | 需先登录云端账号；文字数据按权威序号增量推拉，各设备各自合并 |
| 同步服务端地址 | GitHub Secret `LEMON_SYNC_BASE_URL` | 公开域名，不含任何密钥；本地自测写进 `local.properties` 的同名 Gradle 属性 |
| 对象存储凭据 | Worker Secret | endpoint / region / bucket / prefix / accessKey / secretKey 只存服务端，客户端不接触 |
| 云存储功能开关 | `app/build.gradle.kts` | `IMAGE_CLOUD_SUPPORTED`：仅 release 为 true，debug 不写真实存储桶；`TEXT_SYNC_SUPPORTED`：debug/release 均为 true，文字同步可在 debug 包联调 |

### 同步服务端配置（发布维护用）

服务端的全部密钥都放在 Cloudflare 的 Worker Secret 里，**仓库与安装包中都不出现**：

| Worker Secret | 内容 |
| --- | --- |
| `S3_ENDPOINT` / `S3_REGION` / `S3_BUCKET` / `S3_PREFIX` | 对象存储连接参数（S3 兼容协议，如 Filebase） |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | 对象存储读写凭据，建议使用**最小权限**子账号 |
| `ADMIN_TOKEN` | 管理令牌，仅用于创建 / 重置账号 |
| `MCP_TOKEN` | MCP 接入令牌，给 AI 客户端整理家庭数据用 |

另需绑定一个 D1 数据库（绑定名 `DB`），表结构见 `server/schema.sql`，种子与接口说明见
`server/worker.js` 头部注释。账号用管理接口预置（服务端不回显密码），客户端只能登录。

客户端唯一需要知道的是**公开域名**：构建时经 `LEMON_SYNC_BASE_URL` 注入
`BuildConfig.SYNC_BASE_URL`（当前默认 `https://lemonbox.goodyaoshi.eu.cc`）。
默认域名是免费二级域，存在被 DNS 污染/阻断的风险；客户端因此支持：

- **DoH 解析**：同步域名的「域名 → IP」优先走阿里 / 腾讯 / Cloudflare / Google 的
  DNS over HTTPS，再按域名做 TLS SNI 与证书校验；DoH 全部失败时回退系统 DNS。
- **自定义备用地址 + 自动 fallback**：设置页可填同一 Worker 的备用域名 / 反向代理地址；
  登录与同步前按「自定义地址 → 构建默认地址」顺序探活 `/health`，选第一个可用的。
  换域名不需要重新发版。
- 地址只接受 `https://`；会话里保存登录时的 baseUrl，候选列表之外的地址会让旧令牌失效。

固定解析到 Cloudflare IP 不是可靠方案：anycast IP 会按地区与时间变化，直接连 IP 还会
让证书与 SNI 对不上。DoH 只替换「域名 → IP」这一步，后续仍按域名完成 TLS 校验，
行为与普通 HTTPS 一致。

**历史数据修复**：旧版网页创建的家当载荷缺少 `deletedAt`。App 新版已能容忍并正常解码；
若某台设备的水位已经越过这些记录，需要让服务端历史载荷也补齐，并在设备上重建：

```bash
npx wrangler d1 execute DB --remote --file server/fix-legacy-items.sql
```

然后在 App 的「同步诊断」里执行「以服务端为准重建」（或重置 `PULL_WATERMARK`）。

**登录限流与清理**：同一 IP / 账号 5 分钟内失败 10 次会锁定 5 分钟（429 + `Retry-After`）；
限流表缺失或 D1 异常时自动放行，不会把所有人挡在登录页外。Worker 配置了每日 03:00 的
Cron（`wrangler.jsonc` 的 `triggers.crons`），清理过期会话与 24 小时前的失败桶。

### 多设备数据同步说明

- **默认开启**，但真正的门槛是「是否已登录云端账号」——没登录时不会动作，数据只在本机。
- 服务端以**权威序号** `server_seq` 排序变更：各设备推上本机变更、按水线拉取他人变更，
  再各自做记录级 LWW（`updatedAt` 较新者胜）合并；因合并幂等可交换，各设备最终收敛一致。
- 触发时机：打开 App 时自动 + 登录成功后立即一次 + 后台约每 1 小时一次（任意联网）
  + 设置页「立即同步」。
- 删除靠软删墓碑传播；两边都改过的条目会在同步区提示「有 N 条已取较新的一方」——
  需要逐条对比时用局域网同步（那里有勾选与字段级差异）。
- **拉取上限**：App 每次最多 40 页 × 500 条、Web 最多 60 页 × 500 条；达到上限会在
  回执/设置页提示「还有更多变更，请再次同步」，不再静默当作「已是最新」。
- **偏好冲突**：`PreferencesSnapshot` 现在带 `updatedAt`，偏好内容冲突也按「较新者胜」，
  不再无条件保留服务端版本；`applySynced*` 落地远端内容时不会反过来把时间戳刷新成现在。
- 覆盖范围：物品、分类、位置、记账（含**模板**与**周期记账规则**）、预算、账户、纪念日、
  待办提醒、时间胶囊、打包清单、菜谱与菜单、自定义状态、提醒设置。**图片本体单列**——
  是否上传由「图片上传」开关决定，与文字同步互不影响。

### 图片云端存储说明

- **默认关闭**。未开启时图片只存本机，行为与旧版本一致。
- 开启后图片以「内容 SHA-256」为对象键上传，同一张图在任何设备上都落到同一对象，不会存重复。
- 存储地址与访问密钥**只存在服务端**：客户端登录后，由 Worker 为每个对象单独签发
  几分钟有效的预签名 URL（PUT / GET），客户端全程接触不到长期密钥，包里也没有密文配置。
- 上传成功且云端确认可读后，本地副本会被清理；取回时按需回填。
- **安全提示**：预签名 URL 只在有效期内可用且只对单个对象生效；请为对象存储使用
  **最小权限的读写子账号**（仅授予该 Bucket 的权限），并按需轮换密钥。

### 数据状态说明

- `使用状态`：未使用 / 使用中 / 已用完。
- `处置状态`：在库 / 已借出 / 已送人 / 已丢弃（业务状态，区别于删除）。
- `回收站`：删除后进入软删除区，30 天内可恢复。

### 到期提醒说明

- 提醒阶梯可全局配置，也可按物品单独覆盖。
- WorkManager 按所选时段每日检查，同一物品同一天 / 同一状态只提醒一次。
- 依赖系统通知权限，未授权时不会发送通知。

---

## Contributing

欢迎提 Issue 与 PR：

1. Fork 仓库并新建分支。
2. 保持最小必要改动，避免无关重构。
3. 提交前完成本地构建与测试：`./gradlew test assembleDebug`。
4. Commit message 使用 `feat: / fix: / refactor: / docs: / test: / chore:` 前缀。
5. 涉及 UI 的变更请附截图或录屏；涉及数据库、通知、导航的变更请在 PR 描述中说明风险边界。

---

## 致谢

本项目基于开源项目 [有数 YouShu](https://github.com/gorkys/youshu)（作者 [gorkys](https://github.com/gorkys)，MIT 协议）二次开发而来，感谢原作者与各位贡献者的付出。本项目在原有基础上重制了品牌与视觉、重构并扩展了物品管理、菜谱搭配、到期提醒与局域网同步等能力，并延续 MIT 协议开源。

## License

[MIT](./LICENSE)
