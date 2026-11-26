# Tavernite

原生 Android（Kotlin + Jetpack Compose）SillyTavern 客户端。目标是在手机上获得单手可用的原生聊天体验，同时完整复用 SillyTavern 的前端业务逻辑——世界书、正则、宏、预设、酒馆助手与第三方扩展。

> 当前进度：**Phase 1 已完成**（底座 + 本地回环代理 + 隐藏内核 + JS 桥 + 调试控制台），后续阶段见 [路线图](#路线图)。

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
```

关键点：WebView 永远只访问同一个回环地址，切换主备链路时页面不会重载、会话不会丢失。

## 已实现（Phase 1）

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
  - `ENGINE_READY` / `CHARACTERS_SYNC` / `CHAT_LOADED` / `STREAM_TOKEN` / `MESSAGE_UPDATED` / `GENERATION_STATE` / `PRESETS_SYNC` 事件；原生指令覆盖发送、停止、Swipe、编辑、删除、Slash、作者注释、预设、世界书等。
  - 流式 token 在 JS 侧 40ms 节流，`rawText`（可编辑原文）与 `displayText`（正则后渲染文本）严格分离。
  - 所有 ST API 调用均带特性探测与 DOM 回退，兼容不同 ST 版本。
- Engine Lab 调试页：输入服务器地址与 Header，连接真实 ST，查看角色列表、发送测试消息、实时查看流式输出与代理日志。
- JVM 测试 35 项：端点路由、协议编解码、Header/Cookie/Location 重写、磁盘缓存，以及基于 MockWebServer 的代理集成测试（透传、SSE、缓存、故障切换、502）。

## 构建

环境：JDK 17+（本项目使用 JDK 21）、Android SDK Platform 35、Build-Tools 35。

```powershell
$env:JAVA_HOME="D:/program/scoop/apps/openjdk21/current"
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

## 使用（Phase 1 调试控制台）

1. 安装并打开 Tavernite。
2. 输入 SillyTavern 地址（模拟器访问宿主机用 `http://10.0.2.2:8000`）。
3. 如经 Cloudflare Access / 反代鉴权，按 `Key: Value` 每行填写自定义 Header。
4. 点击 **Connect engine**，等待状态变为 `ready`。
5. 在底部输入框发送测试消息，查看实时流。

## 安全

- 代理只监听 `127.0.0.1`，系统分配随机端口。
- WebView 仅允许访问回环源；明文流量通过 network security config 限定在 `127.0.0.1` / `localhost`。
- Phase 2 起，服务器密码、Basic Auth、CF-Access Token 等将使用 Keystore 支持的加密存储，绝不写入日志。
- 不要求、也不修改服务端 SillyTavern。

## 路线图

- [x] **Phase 1** 底座与通信闭环：代理、隐藏内核、桥、调试页。
- [ ] **Phase 2** Room 离线缓存、对话/角色/服务器 Tab、核心聊天页（气泡/小说双模式、Markdown、Think 折叠、Swipe、智能滚动）。
- [ ] **Phase 3** 消息分段解析器与 HTML 状态栏沙箱（WebViewPool、高度同步、Slash 回传）。
- [ ] **Phase 4** 调校面板、第三方扩展 DOM 聚焦透出、生成期前台服务保活。

## 已知限制

- 本仓库的开发环境没有可访问的真实 SillyTavern 实例，Phase 1 的端到端联调（鉴权反代 + 流式生成）按规格实现并通过代理层集成测试，但**尚未对真实服务器做验收**。
- `bridge.js` 对 ST 内部 API 采用特性探测 + DOM 回退，不同 ST 版本的行为差异需要在真机联调时校准。
- 暂未实现 WebSocket 透传；ST 核心生成链路为 HTTP/SSE，已完整支持。

## 目录

```text
app/src/main/java/com/stmobile/
├── core/proxy/        # 回环代理、上游客户端、缓存、路由
├── core/stengine/     # 隐藏 WebView、桥协议、引擎控制器
├── core/model/        # 领域模型
├── core/settings/     # DataStore 设置
├── feature/server/    # 服务器与 Engine Lab
├── ui/theme/          # Ink & Ember 主题
└── di/                # Hilt
app/src/main/assets/js/bridge.js   # 注入 ST 内核的桥
```
