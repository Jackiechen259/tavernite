# Tavernite

原生 Android（Kotlin + Jetpack Compose）SillyTavern 客户端。目标是在手机上获得单手可用的原生聊天体验，同时完整复用 SillyTavern 的前端业务逻辑——世界书、正则、宏、预设、酒馆助手与第三方扩展。

> 当前进度：**Phase 1–4 已实现并通过构建验证**（`:app:assembleDebug`、`:app:testDebugUnitTest` 45 项测试、`:app:lintDebug`），真机与真实 ST 服务器联调待验收。

## 架构

```text
┌──────────────────────────────────────────────────────────────┐
│ Jetpack Compose 原生 UI（对话 / 角色库 / 调校 / 连接）        │
└──────────────────────────▲───────────────────────────────────┘
                           │ StateFlow / EngineEvent
┌──────────────────────────▼───────────────────────────────────┐
│ ST-Engine：1dp 隐藏 WebView 加载真实 ST 前端 + bridge.js      │
│ Prompt 拼装 / 世界书 / 正则 / 宏 / 第三方扩展全部复用 ST 实现 │
└──────────────────────────▲───────────────────────────────────┘
                           │ http://127.0.0.1:<ephemeral>
┌──────────────────────────▼───────────────────────────────────┐
│ Local Proxy：Ktor Server CIO + OkHttp                        │
│ 自定义 Header / Basic Auth / 自签 CA / SSE 流透传 /          │
│ 静态资源磁盘缓存 / 多链路自动切换 / Cookie 与 Location 重写  │
└──────────────────────────▲───────────────────────────────────┘
                           │ HTTPS / LAN / Mesh VPN
                    [ 远端 SillyTavern ]

消息内嵌 HTML 状态栏 → 沙箱 WebViewPool（ResizeObserver 高度同步）
```

关键点：WebView 永远只访问同一个回环地址，切换主备链路时页面不会重载、会话不会丢失。

## 已实现

### Phase 1 — 底座与通信闭环

- Gradle 8.13 + AGP 8.7.3 + Kotlin 2.0.21 + Compose（Material 3）项目骨架，Hilt 注入。
- `core/proxy`：
  - 全方法透传（GET/POST/PUT/PATCH/DELETE），请求体与查询参数原样转发。
  - 自定义 Header、Basic Auth、PEM 自签 CA（系统 CA + 用户 CA 组合信任）、可选跳过校验。
  - SSE / chunked 流式响应逐块写回，不缓冲。
  - `Set-Cookie` 去掉 `Domain`/`Secure` 并归一化 `SameSite`，`Location`、`Origin`、`Referer` 在回环与上游之间互写。
  - 静态资源（js/css/字体/图片）磁盘缓存 + ETag/Last-Modified 协商。
  - 端点健康退避与自动切换（10s 起指数退避）。
- `core/stengine`：
  - 应用级隐藏 WebView（1dp、透明、不可点击），`WebMessageListener` + `DOCUMENT_START_SCRIPT` 注入 `bridge.js`。
  - 事件与指令覆盖发送、停止、Swipe、编辑、删除、Slash、作者注释、预设、世界书、流式开关、上下文长度、扩展聚焦模式。
  - 流式 token 在 JS 侧 40ms 节流，`rawText`（可编辑原文）与 `displayText`（正则后渲染文本）严格分离。
  - 所有 ST API 调用均带特性探测与 DOM 回退，兼容不同 ST 版本。

### Phase 2 — 原生 UI 与离线优先

- Room 持久化 `ServerProfile` / `Character` / `ChatSession` / `Message`，冷启动立即渲染缓存。
- Tink + Android Keystore 加密保存自定义 Header、Basic Auth、自签 CA。
- 4 个底部 Tab：
  - **对话**：最近会话、全文搜索、置顶、左滑归档、长按删除。
  - **角色库**：3:4 双列立绘、标签筛选、PNG/JSON 角色卡导入（经回环代理上传 `/api/characters/import`）。
  - **调校**：预设切换、世界书开关、流式开关、上下文长度、作者注释与扩展入口。
  - **连接**：多服务器 Profile、优先级端点、链路延迟探测、Header/Basic Auth/CA、主题、缓存清理。
- 聊天页：
  - 气泡 / 小说双阅读模式，Markdown 自渲染（标题、强调、代码、列表、引用、链接）。
  - `<think>` 折叠卡片、分支指示器、左右滑动切换 Swipe、长按操作菜单（编辑原文 / Fork / 继续 / 重生成 / 复制 / 删除 / 截断）。
  - 流式自动滚动 + 用户上滑立即停止 + 回到底部按钮；`imePadding` / Edge-to-Edge。
  - `+` 扩展面板：Author's Note、Persona、Quick Replies、相册/拍照附件。

### Phase 3 — 混合渲染与 HTML 状态栏沙箱

- `MessageBlockParser`：按顺序切分 Think / HtmlWidget / Markdown，支持流式未闭合标签与稳定 SHA-256 块哈希。
- `WebViewPool`：最多复用 4 个沙箱 WebView，滑出视口回收并重置 `about:blank`。
- `widget_sandbox_helper.js`：ResizeObserver 高度回传、内部滚动禁用、横向手势防冲突、`triggerSlash`/`data-slash` 回传原生。
- 倒数第 5 条以前的旧状态栏默认折叠为“点击展开状态栏”。

### Phase 4 — 调校、插件透出与后台保活

- 调校面板直接调用引擎指令：`SET_PRESET`、`TOGGLE_WORLD_INFO`、`SET_STREAMING`、`SET_CONTEXT_LENGTH`、`UPDATE_AUTHOR_NOTE`、`EXECUTE_SLASH_CMD`。
- 第三方扩展“DOM 聚焦透出”：把运行中的引擎 WebView 临时重挂到全屏 Dialog，注入 `extension_mobile.css` 隐藏 ST 顶栏与聊天区，关闭后恢复 1dp 隐藏宿主且不中断状态。
- `GenerationForegroundService`（`dataSync`）：生成开始时常驻通知，通知栏“停止”直接触发 `STOP_GENERATION`，生成结束自动停止。

## 构建

环境：JDK 17+、Android SDK Platform 35、Build-Tools 35。

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

> 已验证：JDK 17 + Android SDK 35 下 `:app:assembleDebug` 成功（APK 约 22 MB），`:app:testDebugUnitTest` 45 项通过，`:app:lintDebug` 无 error。真机安装、真实 ST 服务器鉴权/流式生成与第三方扩展仍需实机验收。

## 使用

1. 安装并打开 Tavernite。
2. 进入 **连接**，点击 `+` 添加服务器：
   - 服务器地址一行一个，按优先级排列（LAN > Mesh VPN > 公网反代）。
   - 如经 Cloudflare Access / 反代鉴权，按 `Key: Value` 每行填写自定义 Header。
   - 自签名证书可粘贴 PEM 或从文件导入。
3. 保存并连接，等待顶部状态变为“就绪”。
4. 在 **角色库** 打开角色，或直接在 **对话** 打开最近会话。
5. 在 **调校** 中切换预设/世界书；右上角扩展图标可打开 ST 第三方扩展面板。

调试入口：**连接** 页右上角虫子图标打开 Engine Lab，可直接输入地址、查看事件流与流式输出。

## 安全

- 代理只监听 `127.0.0.1`，系统分配随机端口。
- 隐藏内核 WebView 仅加载回环源；上游请求由 OkHttp 直连，LAN 场景允许明文 HTTP，HTTPS / 自签 CA 场景仍可校验。
- 服务器密码、Basic Auth、CF-Access Token、自定义 Header、CA 使用 Tink AES-256-GCM + Android Keystore 加密存储，不写入日志。
- 不要求、也不修改服务端 SillyTavern。

## 路线图

- [x] **Phase 1** 底座与通信闭环：代理、隐藏内核、桥、调试页。
- [x] **Phase 2** Room 离线缓存、对话/角色/服务器 Tab、核心聊天页。
- [x] **Phase 3** 消息分段解析器与 HTML 状态栏沙箱。
- [x] **Phase 4** 调校面板、第三方扩展 DOM 聚焦透出、生成期前台服务保活。

## 已知限制

- 本仓库的开发环境没有可访问的真实 SillyTavern 实例，Phase 1–4 的端到端联调（鉴权反代 + 流式生成 + 状态栏按钮）按规格实现，但**尚未对真实服务器做验收**。
- `bridge.js` 对 ST 内部 API 采用特性探测 + DOM 回退，不同 ST 版本的行为差异需要在真机联调时校准；快捷回复、世界书启用状态的读取即是尽力而为。
- 角色卡导入按 ST `/api/characters/import`（multipart `avatar` + `file_type`）实现，若目标版本端点不同，需要在 `CharactersViewModel` 中适配。
- 暂未实现 WebSocket 透传；ST 核心生成链路为 HTTP/SSE，已完整支持。
- 旧会话列表由角色同步 + `CHAT_LOADED` 增量构建；尚未调用 ST 的 recent-chats 接口枚举同角色的全部历史分支。

## 目录

```text
app/src/main/java/com/stmobile/
├── AppViewModel.kt / TaverniteApp.kt / MainActivity.kt
├── core/database/      # Room 实体、DAO、数据库
├── core/model/         # 领域模型
├── core/parser/        # MessageBlockParser
├── core/proxy/         # 回环代理、上游客户端、缓存、路由
├── core/repository/    # 离线缓存与引擎事件同步
├── core/security/      # Tink 加密存储
├── core/settings/      # DataStore 设置
├── core/stengine/      # 隐藏 WebView、桥协议、引擎控制器
├── feature/chats/      # 对话列表
├── feature/characters/ # 角色库
├── feature/chatroom/   # 聊天页、Markdown、Think、输入栏
├── feature/widgetsandbox/ # WebViewPool 与 HTML 状态栏
├── feature/tuning/     # 调校
├── feature/server/     # 服务器、Engine Lab、扩展透出
├── service/            # 生成期前台服务
├── ui/theme/           # Ink & Ember 主题
└── di/                 # Hilt
app/src/main/assets/js/bridge.js              # 注入 ST 内核的桥
app/src/main/assets/js/widget_sandbox_helper.js
app/src/main/assets/css/extension_mobile.css
```

## 发版

1. 首次发版前生成并备份 release keystore：

   ```bash
   ./scripts/generate-keystore.sh
   ```

2. 在 GitHub 仓库 Settings → Secrets and variables → Actions 中添加：

   - `RELEASE_KEYSTORE_BASE64`：脚本输出的 base64
   - `RELEASE_STORE_PASSWORD`
   - `RELEASE_KEY_ALIAS`
   - `RELEASE_KEY_PASSWORD`（与 `RELEASE_STORE_PASSWORD` 相同；JDK 17 的 PKCS12 不支持独立 key password）

3. 在 `main` 上打 tag 并推送：

   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

4. workflow 会运行单元测试、构建签名 APK，并创建 GitHub Release，资产为
   `Tavernite-<版本>-<versionCode>.apk` 与 `.sha256`。

> keystore 与密码必须备份。丢失后已安装用户无法覆盖升级，只能卸载重装。
> 本地 release 构建：在根目录创建被 gitignore 的 `keystore.properties`，然后
> `./gradlew :app:assembleRelease -PversionName=0.1.1 -PversionCode=101`。

## 自动更新

release 构建内置应用内更新（`core/update`）；debug 构建完全不参与检查、下载与安装。

### 检查时机

- **启动自动检查**：应用启动后在后台检查，最多每 24 小时一次。每次尝试（包括失败）都会记录时间，避免离线时每次启动都请求网络；失败不影响启动也不打扰用户。
- **手动检查**：**连接** → **关于** → **检查更新**。手动检查不受 24 小时节流限制，也不受“跳过此版本”影响；`已是最新` 或检查失败以本页提示反馈，不弹全局弹窗。
- **开关**：**连接** → **关于** 的“启动时自动检查更新”可关闭启动检查（默认开启）。

### 更新流程

1. 发现新版本后弹出更新弹窗，展示版本号、更新日志（GitHub Release 说明）与安装包大小。
2. 点击“下载并安装”，APK 下载到应用缓存目录，下载过程中同步计算 SHA-256。
3. 若 Release 提供了 `<apk>.sha256` 资产，则下载并比对校验值；不一致会提示“安装包校验失败”并删除下载文件。
4. 校验通过后交给系统安装器覆盖安装；应用只从 GitHub API 返回的 `github.com` / `*.githubusercontent.com` HTTPS 地址下载。
5. 首次安装需要允许 Tavernite“安装未知应用”。未授权时应用会跳转系统授权页；授权后回到 **连接** → **关于** 或弹窗点击“安装”继续。

### “稍后”“跳过此版本”与重试

- **稍后**：只关闭弹窗，仅对当前进程有效（内存状态，不持久化）；本次进程内可从 **关于** 重新打开（`Available` 时点“查看”，已下载时点“安装”）。进程重启后更新状态重置，需等待下一次自动检查（距上次检查至少 24 小时）或手动检查。
- **跳过此版本**：持久化保存；自动检查不再提示该版本，手动检查仍会显示。发布更新的版本后会重新提示。
- **下载中**：弹窗不能点击外部关闭，只能“取消”；取消或失败后可在 **关于** 重新进入（`Available` 时点“查看”再次下载，下载失败时点“重试”）。
- **已下载未安装**：下载完成后关闭弹窗不会丢失状态，**连接** → **关于** 会保留“安装”入口。该状态不跨进程保存：应用重启后需重新检查/下载（下次下载前会清理旧缓存）。

### 包名、构建类型与仓库配置

- release 包名 `com.stmobile`，debug 包名 `com.stmobile.debug`，两者可共存，互不覆盖。
- 更新功能只在 release 构建启用；debug 构建的“关于”区显示“调试构建不参与自动更新”，不会检查、下载或安装。
- GitHub API 为公开接口，**无需配置 token**。仓库与节流参数在 `app/src/main/java/com/stmobile/core/update/UpdateConfig.kt`（`OWNER`、`REPO`、`CHECK_INTERVAL_MS`）；若仓库改名，必须同步修改 `OWNER`/`REPO`。若仓库转为私有，匿名 API 会返回 404，应用内更新将不可用（本应用不内置 token），手动检查会提示错误并保留“在 GitHub 查看”入口。

### 手工验收清单（需维护者真机执行）

以下步骤依赖 GitHub Secrets、真实 tag 与真机安装，尚未在本地开发环境执行：

0. 已核实：仓库当前没有 tag/Release，既有安装均为 debug 包（`com.stmobile.debug`），与 release 包名不同，因此首个 CI Release 不需要覆盖任何历史安装，可直接从 v0.1.0 开始。
1. 生成 keystore（`./scripts/generate-keystore.sh`），按“发版”一节配置 4 个 GitHub Secrets。
2. 在 `main` 上推送 `v0.1.0`，确认创建了 GitHub Release，资产包含 `Tavernite-0.1.0-100.apk` 与 `Tavernite-0.1.0-100.apk.sha256`。
3. 在手机上手动下载并安装该 release APK（首次需允许“安装未知应用”）。
4. 再推送 `v0.1.1`，确认 workflow 的两道门禁通过：tag 位于 `main` 历史、versionCode（101）高于 latest release（100）。
5. 打开应用，确认自动或手动检查发现 v0.1.1 → 显示更新日志 → 下载 → SHA-256 校验 → 系统安装器 → 覆盖安装成功；重新打开后会话、角色与服务器配置等数据保留。
6. 验证交互：点“稍后”后弹窗关闭，在 **关于** 点“查看”可再次打开；点“跳过此版本”后自动检查不再提示该版本、手动检查仍提示；关闭“启动时自动检查更新”后启动不再自动检查；下载完成后关闭弹窗，可从 **连接** → **关于** 继续安装。

> **务必备份 keystore 与密码。** 丢失后新 APK 签名不同，已安装用户无法覆盖升级，只能卸载重装（会清除应用内数据）。
