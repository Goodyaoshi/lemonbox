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

> 数据默认**只保存在手机本机**，不依赖 Google、百度、腾讯、OpenAI 等任何厂商云服务。局域网同步仅在家内网直连完成。
>
> 可选开启「图片云端存储」后，家当图片会上传到你自己的 S3 兼容对象存储（如 Cloudflare R2 / Filebase），
> 用于多设备共享与节省手机空间；**该功能默认关闭，且只在你填入自己的访问凭据后才会上传**。
> 文字数据（物品、记账、纪念日等）始终不上传——多设备间的一致性通过加密对象存储上的快照文件同步，
> 对象存储地址随包内置，你只需提供自己的凭据。

---

## 功能特点

- 📸 **拍照 / 条码快速录入**：从拍照、扫码到保存尽量压缩操作路径，适合高频、碎片化记录。
- 🏠 **首页一览**：搜索框输入即筛选，今天的事、纪念日、今天吃什么、本月账一屏掌握。
- 🍳 **菜谱与每周菜单**：内置家常菜谱库（主食 / 荤菜 / 素菜 / 汤饮 TAG 筛选），按现有食材自动搭配每周菜单，保证主食、蛋白、蔬菜齐全；点「做了」自动扣减对应食材，缺的食材一键进入待买清单。
- ⏰ **到期提醒**：提醒阶梯（1/3/7/14/30/60/90 天）支持全局默认 + 按物品覆盖，每日固定时段（默认 08:00 / 19:00）合并通知，同一物品同一天只提醒一次。
- 🗂 **分类与位置双维度管理**：分类带图标、支持多级结构（内置受保护的食品 / 零食等分类树），存放位置独立管理，互不干扰。
- 📦 **家当状态体系**：使用状态（未使用 / 使用中 / 已用完）、处置状态（在库 / 已借出 / 已送人 / 已丢弃）与补货标记三个正交维度；物品支持数量与单位（3 瓶、2 箱），侧滑即可「用 1 件」或「用完」。
- 🛒 **待买清单**：手动添加或由菜谱缺食材自动加入，带单位与分类关联，可按分类筛选。
- ♻️ **回收站**：删除后 30 天内可恢复，超期自动清理。
- 📶 **局域网同步**：手机之间在内网直连同步，合并式导入导出（按 syncId 对齐、updatedAt 解决冲突），换机不丢数据。
- ✨ **沉浸式 UI**：柠檬系配色、大圆角卡片、毛玻璃悬浮导航、沉浸式顶部布局，支持浅色 / 深色 / 跟随系统。

---

## 技术栈

- Kotlin + Coroutines / Flow
- Jetpack Compose（Material 3）+ Navigation Compose
- Room（含 schema 导出与版本迁移）
- Hilt
- WorkManager（到期提醒）
- CameraX（拍照录入）
- ZXingLite（条码扫描）
- Coil（图片加载）
- kotlinx.serialization（备份与菜谱序列化）
- Haze（毛玻璃效果）
- NanoHTTPD（局域网同步服务）

---

## 项目结构

```text
.
├─ app/
│  ├─ src/main/java/com/goodyaoshi/lemonbox
│  │  ├─ data/
│  │  │  ├─ local/          # Room 实体、DAO、数据库与迁移
│  │  │  ├─ backup/         # 合并式导入导出
│  │  │  ├─ meal/           # 菜谱、一餐搭配与每周菜单
│  │  │  ├─ repository/     # 数据仓库层
│  │  │  ├─ search/         # 本地搜索解析与过滤
│  │  │  ├─ settings/       # 应用设置（SharedPreferences + StateFlow）
│  │  │  └─ sync/           # 局域网同步（客户端 / 服务端）
│  │  ├─ di/                # Hilt 依赖注入
│  │  ├─ ui/
│  │  │  ├─ components/     # 通用 UI 组件
│  │  │  ├─ navigation/     # 路由与导航
│  │  │  ├─ scan/           # 条码扫描
│  │  │  ├─ screen/         # 页面（home / recipe / category / detail / settings 等）
│  │  │  └─ viewmodel/      # 状态与交互逻辑
│  │  ├─ util/              # 通知、日期、图片等工具
│  │  ├─ LemonApplication.kt
│  │  └─ MainActivity.kt
│  └─ src/main/res/         # 图标、主题、字符串、图片资源
└─ app/schemas/             # Room 数据库 schema 导出
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
3. 菜谱 -> TAG 筛选 -> 加入每周菜单 -> 点「做了」看食材自动扣减
4. 我的 -> 待买清单 / 回收站 / 分类与状态 / 局域网同步 / 设置

---

## 配置说明

本项目以本地功能为主，无对外 API。关键配置如下：

| 配置项 | 位置 | 说明 |
| --- | --- | --- |
| `applicationId` | `app/build.gradle.kts` | `com.goodyaoshi.lemonbox` |
| `minSdk` / `targetSdk` | `app/build.gradle.kts` | `26 / 35` |
| 本地数据库 | Room（`lemonbox.db`） | 分类 / 位置含受保护种子数据，schema 随版本迁移 |
| 应用设置 | SharedPreferences | 提醒阶梯、提醒时段、主题模式、每周菜单等 |
| 通知权限 | Android 13+ | 到期提醒依赖 `POST_NOTIFICATIONS` |
| 相机权限 | 首次拍照时申请 | 拍照录入依赖 CameraX |
| 局域网权限 | 本地网络 | 同步仅访问内网，cleartext 限定本地网络 |
| 图片云端存储 | 可选，默认关闭 | 需自备 S3 兼容对象存储的访问凭据；连接参数（endpoint / region / bucket / prefix）随包内置，保证各设备指向同一处存储 |
| 云存储凭据 | Android Keystore | AccessKey / SecretKey 以 AES-256-GCM 加密后存本机，不进备份、不进日志 |

### 图片云端存储说明

- **默认关闭**。未开启时图片只存本机，行为与旧版本一致。
- 开启后图片以「内容 SHA-256」为对象键上传，同一张图在任何设备上都落到同一对象，不会存重复。
- 用户只需填写访问凭据（AccessKey / SecretKey）；存储地址等连接参数随包内置，
  避免各设备配置不一致导致互相看不到对方的图片。
- 上传成功且云端确认可读后，本地副本会被清理；取回时按需回填。
- **安全提示**：客户端直存长期 AccessKey 存在被逆向提取的风险（阿里云等厂商官方均不推荐）。
  建议使用**最小权限的 RAM 子账号**，仅授予该 Bucket 的读写权限。长期方案应改为由服务端签发
  STS 临时凭证或预签名 URL。

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
