# Tavernite — Native Android SillyTavern Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a pure-native Kotlin/Jetpack Compose Android client for SillyTavern that keeps ST's real frontend logic (World Info, regex, macros, presets, extensions) running in a hidden WebView engine, with a local loopback proxy and sanitized HTML-widget sandbox rendering.

**Architecture:** Four layers: (1) Compose native UI; (2) headless ST-Engine WebView loading the real ST frontend through a local proxy, bridged over `WebMessageListener` JSON envelopes; (3) Ktor CIO loopback proxy + OkHttp upstream with header injection, custom CA trust, SSE pass-through, static asset cache and multi-endpoint failover; (4) per-widget sandboxed WebViews with height bridge and slash-command routing. Room provides offline-first cache; DataStore + Tink-encrypted prefs hold settings and secrets.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.3, Gradle 8.13, Jetpack Compose (BOM 2024.12.01), Material 3, Hilt 2.52 (KSP), Room 2.6.1, DataStore 1.1.1, Ktor Server CIO 3.0.3, OkHttp 4.12.0, androidx.webkit 1.12.1, kotlinx.serialization 1.7.3, Coil 3.0.4, Tink 1.15.0, JUnit 4.13.2.

## Global Constraints

- `minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`, Kotlin 2.x, single `:app` module, package `com.stmobile`.
- MVVM/MVI + unidirectional data flow (`StateFlow` / `SharedFlow`).
- **Never** rely on `WebViewClient.shouldInterceptRequest` for proxying business requests (no POST body access) — always the loopback proxy.
- `MessageDTO` always carries both `rawText` (editable Markdown) and `displayText` (post-regex/macro render).
- `STREAM_TOKEN` is throttled 30–50 ms in JS; Compose lists always use stable `key`s.
- Secrets (passwords, Basic Auth, CF-Access tokens, custom headers) encrypted at rest; never logged in plaintext.
- WebViews are lifecycle-bound; no Activity Context WebView held by ViewModels.
- No server-side ST modifications; client-only injection.
- Every phase must compile (`assembleDebug`) before moving on; core logic needs JVM unit tests.
- Commit and push to `origin/main` at every phase completion.

## File Structure

```text
settings.gradle.kts / build.gradle.kts / gradle.properties / gradle/libs.versions.toml
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/values/{strings,themes}.xml, res/xml/network_security_config.xml
app/src/main/assets/js/{bridge.js, widget_sandbox_helper.js}, assets/css/extension_mobile.css
app/src/main/java/com/stmobile/
  MainActivity.kt, STApplication.kt, TaverniteApp.kt
  core/model/Models.kt
  core/proxy/{LocalProxyServer,ProxyConfig,EndpointRouter,UpstreamClient,DiskAssetCache,ProxyModule}.kt
  core/stengine/{STEngineManager,EngineEvents,BridgeProtocol,StreamThrottle}.kt
  core/parser/MessageBlockParser.kt
  core/database/{Entities,Daos,AppDatabase,Converters}.kt
  core/security/SecurePrefs.kt
  core/settings/SettingsStore.kt
  core/repository/{ServerRepository,CharacterRepository,ChatRepository,TuningRepository}.kt
  feature/chats/{ChatsScreen,ChatsViewModel}.kt
  feature/characters/{CharactersScreen,CharactersViewModel}.kt
  feature/chatroom/{ChatRoomScreen,ChatRoomViewModel,MessageBubble,MarkdownText,ThinkCard,InputBar,PlusPanel}.kt
  feature/widgetsandbox/{WidgetSandbox,WebViewPool,HtmlWidgetContainer}.kt
  feature/tuning/{TuningScreen,TuningViewModel}.kt
  feature/server/{ServerScreen,ServerViewModel,ExtensionOverlay}.kt
  feature/common/{EngineStatusBar,EmptyState}.kt
  ui/theme/{Theme,Type,Color}.kt
  service/GenerationForegroundService.kt
  di/AppModule.kt
app/src/test/java/com/stmobile/{MessageBlockParserTest,BridgeProtocolTest,EndpointRouterTest,ProxyRulesTest}.kt
```

---

## Task 1: Project scaffold and toolchain

**Files:** `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `res/values/themes.xml`, `res/xml/network_security_config.xml`, wrapper files, `STApplication`, `MainActivity` placeholder, `ui/theme/*`.

**Interfaces produced:** a compiling app shell; `TaverniteApplication` with Hilt; theme composables `TaverniteTheme`.

- [x] Generate Gradle wrapper 8.13 using the locally cached distribution.
- [x] Declare all versions above; enable Compose + KSP; `jvmTarget=17`; Compose compiler plugin 2.0.21.
- [x] Manifest: INTERNET, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS; `usesCleartextTraffic` only for `127.0.0.1` via network security config.
- [ ] Verify: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] Commit `chore: scaffold Android project`.

## Task 2: Models, secure prefs, settings

**Files:** `core/model/Models.kt`, `core/security/SecurePrefs.kt`, `core/settings/SettingsStore.kt`, `feature/server/ServerModels.kt`.

**Interfaces produced:**
```kotlin
data class ServerProfile(val id: String, val name: String, val endpoints: List<String>,
  val customHeaders: Map<String, String>, val basicAuthUser: String?, val basicAuthPassword: String?,
  val caPem: String?, val allowInsecureTls: Boolean, val cacheEnabled: Boolean)
data class Character(val id: String, val name: String, val avatarUrl: String?, val tags: List<String>, val lastMessageDate: Long?)
data class ChatSession(val id: String, val characterId: String, val title: String, val lastMessage: String, val lastMessageDate: Long, val pinned: Boolean, val archived: Boolean)
data class Message(val id: Int, val chatId: String, val role: MessageRole, val rawText: String, val displayText: String, val swipeId: Int, val swipeCount: Int, val timestamp: Long, val isStreaming: Boolean)
enum class MessageRole { USER, ASSISTANT, SYSTEM }
sealed interface MessageBlock { data class Markdown(val content: String): MessageBlock; data class Think(val content: String, val isStreaming: Boolean): MessageBlock; data class HtmlWidget(val html: String, val blockHash: String): MessageBlock }
```
`SecurePrefs.putSecret/getSecret` = Tink AES256-GCM keyed by Android Keystore. `SettingsStore` = DataStore<Preferences> (theme, reading mode, active profile, cache).

- [ ] Unit test: `SecurePrefs` round-trip is Android-only → covered by build; test serializable profile secrets JSON encryption helper on JVM.
- [ ] Commit `feat: models, secure storage, settings`.

## Task 3: Loopback proxy (core risk)

**Files:** `core/proxy/ProxyConfig.kt`, `EndpointRouter.kt`, `UpstreamClient.kt`, `DiskAssetCache.kt`, `LocalProxyServer.kt`.

**Interfaces produced:**
```kotlin
class EndpointRouter(endpoints: List<String>) {
  fun current(): String
  fun reportSuccess(latencyMs: Long)
  fun reportFailure(): String?   // returns next candidate or null
  suspend fun probe(client: OkHttpClient): List<EndpointHealth>
}
data class ProxyConfig(val endpoints: List<String>, val headers: Map<String,String>, val basicAuth: String?,
  val caPem: String?, val allowInsecureTls: Boolean, val cacheEnabled: Boolean)
class LocalProxyServer(private val upstream: UpstreamClient) {
  suspend fun start(config: ProxyConfig): Int   // returns bound 127.0.0.1 port
  fun updateConfig(config: ProxyConfig)
  suspend fun stop()
  val events: SharedFlow<ProxyEvent>            // StreamStarted/StreamEnded/UpstreamError
}
```
Behavior: method-agnostic `/{path...}` route, raw body pass-through, hop-by-hop header stripping, `Accept-Encoding` normalized (OkHttp gunzip), Cookie/Set-Cookie pass-through, `Location` rewrite to local origin, SSE/chunked streaming with per-chunk flush, 200-GET static asset disk cache with ETag/Last-Modified revalidation, auto-endpoint failover, optional custom CA via TrustManager.

- [ ] Unit tests: header filter, location rewrite, cache key, router failover ordering.
- [ ] Verify proxy compiles; commit `feat: loopback proxy with streaming, cache and failover`.

## Task 4: ST engine + bridge protocol

**Files:** `assets/js/bridge.js`, `core/stengine/{BridgeProtocol,EngineEvents,STEngineManager}.kt`, `core/model/BridgeDtos.kt`.

**Interfaces produced:**
```kotlin
sealed interface EngineEvent { object Ready; data class CharactersSync(val list: List<Character>); data class ChatLoaded(val chatId:String, val characterId:String, val messages: List<Message>); data class StreamToken(val messageId:Int, val swipeId:Int, val rawText:String, val displayText:String); data class MessageUpdated(val messageId:Int, val message: Message); data class GenerationState(val isGenerating:Boolean, val reason:String?); data class PresetsSync(val activePreset:String?, val presets:List<String>, val activeWorldInfo:List<String>, val personas:List<String>); data class Error(val message:String) }
class STEngineManager { val events: SharedFlow<EngineEvent>; val state: StateFlow<EngineState>; suspend fun attach(host: ViewGroup, localOrigin: String); fun send(type:String, payload: JsonObject); fun load(url:String) }
```
`bridge.js`: poll for `SillyTavern.getContext()`, emit `ENGINE_READY`; hook `context.eventSource` (`event_types`); MutationObserver throttled 40 ms emits `STREAM_TOKEN` with raw from `context.chat` + display from DOM; handle Native→JS commands with context APIs first, DOM fallbacks second (`SELECT_CHARACTER`, `SEND_MESSAGE`, `STOP_GENERATION`, `SWIPE_MESSAGE`, `EDIT_MESSAGE`, `DELETE_MESSAGE`, `CONTINUE_GENERATION`, `EXECUTE_SLASH_CMD`, `UPDATE_AUTHOR_NOTE`, `SET_PRESET`, `TOGGLE_WORLD_INFO`). Every exchange uses `{type, requestId?, payload}` JSON envelopes via `STNativeBridge`.

- [ ] Unit test: envelope encode/decode and event mapping.
- [ ] Commit `feat: ST engine bridge protocol`.

## Task 5: Phase 1 debug console

**Files:** `feature/server/DebugConsoleScreen.kt`, `server/DebugViewModel.kt`, `MainActivity` wiring, `STApplication`, DI.

UI: server URL + headers input, connect, engine state, character list, send message, live streaming transcript. Proxy/engine lifecycle owned by an application-scoped `EngineController`.

- [ ] Verify: `./gradlew :app:assembleDebug testDebugUnitTest` → success.
- [ ] Push Phase 1; acceptance note: requires real ST server for end-to-end validation.

## Task 6: Room + offline-first repositories

**Files:** `core/database/*`, `core/repository/*`.

Entities: servers, characters, chats, messages (+ indices, converters). Repositories expose `Flow<List<...>>`, upsert-sync from engine events, `Message.rawText` preserved. Cold start renders cache immediately; engine sync merges by timestamp.

- [ ] Unit test: DTO→entity→domain mapping, merge rules.
- [ ] Commit `feat: offline-first Room cache`.

## Task 7: Main navigation, Chats, Characters, Server tabs, Chat room

**Files:** `TaverniteApp.kt`, feature screens/VMs, `ui/components/*`.

- Chats: search, pin, archive/delete via swipe.
- Characters: 3:4 `LazyVerticalGrid`, tag filter chips, FAB PNG/JSON import (SAF, copy JSON/PNG into ST via proxy upload endpoint or engine slash `/import`; fallback documented).
- Server: profile CRUD, endpoint priority list, latency probe, custom headers, CA PEM import (SAF), theme.
- Chat room: bubble/novel modes, Markdown renderer with streaming-safe keys, think cards, swipe gestures + branch indicator, long-press sheet, smart auto-scroll with "back to bottom", `imePadding`, input bar + plus panel (Author's Note/Persona/Quick Replies/attachments).

- [ ] Verify build + unit tests; manual Compose previews compile.
- [ ] Push Phase 2.

## Task 8: Parser + widget sandbox

**Files:** `core/parser/MessageBlockParser.kt`, `feature/widgetsandbox/{WidgetSandbox,WebViewPool,HtmlWidgetContainer}.kt`, `assets/js/widget_sandbox_helper.js`.

Parser: ordered blocks for `<think|thinking>`, fenced ```html blocks and HTML container segments, markdown remainder; stable SHA-256 block hashes. Sandbox: pool of ≤4 WebViews, `https://widgets.local` base URL, `STWidgetBridge` message listener for height + slash commands, `ResizeObserver`, touch disallow-intercept, collapse placeholder beyond 5th-from-last message.

- [ ] Unit tests: parser cases (think + html + markdown, streaming think, code fences).
- [ ] Verify build; push Phase 3.

## Task 9: Tuning, extension overlay, foreground service

**Files:** `feature/tuning/*`, `feature/server/ExtensionOverlay.kt`, `assets/css/extension_mobile.css`, `service/GenerationForegroundService.kt`.

- Tuning: preset switch, world info toggles, streaming toggle, context slider (slash cmd), advanced section, extension entry.
- Overlay: reparent live engine WebView into full-screen sheet, inject CSS hiding ST chrome and pinning `#extensions_settings`, restore afterwards.
- Service: start on GENERATION_STATE(true), `dataSync` type, notification with Stop → `STOP_GENERATION`; stop on end.
- [ ] Verify `assembleDebug`, `testDebugUnitTest`, `lintDebug` (best effort); final push Phase 4.

## Verification Playbook

```bash
JAVA_HOME="D:/program/scoop/apps/openjdk21/current" ./gradlew :app:assembleDebug
JAVA_HOME="D:/program/scoop/apps/openjdk21/current" ./gradlew :app:testDebugUnitTest
```

## Known Risks / Deviations

- No real SillyTavern instance available in this environment → end-to-end streaming/auth acceptance is implemented to spec but **not live-verified**; bridge uses feature detection + DOM fallbacks.
- Third-party markdown renderer replaced by a self-contained Compose Markdown renderer to avoid Kotlin/Compose version conflicts; behavior (headings, emphasis, code, lists, quotes, links) preserved.
- WebSocket pass-through deferred; ST's core generation path is SSE/HTTP and is fully streamed.
