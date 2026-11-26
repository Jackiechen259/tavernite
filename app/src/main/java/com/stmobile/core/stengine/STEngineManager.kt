package com.stmobile.core.stengine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.stmobile.BuildConfig
import com.stmobile.core.model.EnginePhase
import com.stmobile.core.model.EngineStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the hidden ST frontend WebView. Application-scoped: the WebView is
 * created with the application context and never held by a ViewModel.
 */
@Singleton
class STEngineManager @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    companion object {
        private const val TAG = "STEngine"
        const val BRIDGE_CHANNEL = "STNativeBridge"
        private const val BRIDGE_ASSET = "js/bridge.js"
    }

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var engineHost: ViewGroup? = null
    private var localOrigin: String = ""
    private var chatId: String = ""
    private var bridgeScript: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(host: ViewGroup) {
        mainHandler.post {
            engineHost = host
            val existing = webView
            if (existing != null) {
                (existing.parent as? ViewGroup)?.removeView(existing)
                host.addView(existing, 1, 1)
                return@post
            }
            if (localOrigin.isEmpty()) return@post
            val view = createWebView(localOrigin)
            webView = view
            host.addView(view, 1, 1)
        }
    }

    fun load(url: String) {
        val origin = originRule(url)
        mainHandler.post {
            if (origin != localOrigin) {
                localOrigin = origin
                webView?.destroy()
                webView = null
                val host = engineHost
                if (host != null) {
                    val view = createWebView(origin)
                    webView = view
                    (view.parent as? ViewGroup)?.removeView(view)
                    host.addView(view, 1, 1)
                }
            }
            _status.value = EngineStatus(phase = EnginePhase.LOADING_ENGINE)
            webView?.loadUrl(url)
        }
    }

    fun reload() {
        mainHandler.post { webView?.reload() }
    }

    fun send(
        type: String,
        payload: JsonObject = JsonObject(emptyMap()),
        requestId: String? = null
    ) {
        val command = BridgeProtocol.encodeCommand(type, payload, requestId)
        val literal = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(command))
        mainHandler.post {
            webView?.evaluateJavascript(
                "window.__taverniteReceive && window.__taverniteReceive($literal)",
                null
            )
        }
    }

    fun reset() {
        chatId = ""
        mainHandler.post {
            webView?.loadUrl("about:blank")
            _status.value = EngineStatus()
        }
    }

    fun reparentTo(container: ViewGroup) {
        mainHandler.post {
            val view = webView ?: return@post
            (view.parent as? ViewGroup)?.removeView(view)
            view.alpha = 1f
            container.addView(
                view,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    fun restoreToEngineHost() {
        mainHandler.post {
            val view = webView ?: return@post
            val host = engineHost ?: return@post
            (view.parent as? ViewGroup)?.removeView(view)
            view.alpha = 0f
            host.addView(view, 1, 1)
        }
    }

    fun currentWebView(): WebView? = webView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(origin: String): WebView {
        val view = WebView(appContext)
        view.setBackgroundColor(Color.TRANSPARENT)
        view.alpha = 0f
        view.isClickable = false
        view.isFocusable = false
        view.overScrollMode = WebView.OVER_SCROLL_NEVER
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "$userAgentString Tavernite/${BuildConfig.VERSION_NAME}"
        }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        CookieManager.getInstance().setAcceptCookie(true)

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectBridgeFallback(view)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val message = error?.description?.toString() ?: "WebView load error"
                    _status.value = EngineStatus(EnginePhase.ERROR, message)
                    _events.tryEmit(EngineEvent.EngineError(message))
                }
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    Log.d(TAG, "${consoleMessage.messageLevel()}: ${consoleMessage.message()}")
                }
                return true
            }
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                view,
                BRIDGE_CHANNEL,
                setOf(origin),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: androidx.webkit.JavaScriptReplyProxy
                    ) {
                        message.data?.let(::handleRawMessage)
                    }
                }
            )
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val script = readBridgeScript() ?: return view
            WebViewCompat.addDocumentStartJavaScript(view, script, setOf(origin))
        }
        return view
    }

    private fun injectBridgeFallback(view: WebView?) {
        if (view == null) return
        val script = readBridgeScript() ?: return
        view.evaluateJavascript(script, null)
    }

    private fun readBridgeScript(): String? {
        bridgeScript?.let { return it }
        return runCatching {
            appContext.assets.open(BRIDGE_ASSET).bufferedReader().use { it.readText() }
                .also { bridgeScript = it }
        }.getOrElse {
            Log.e(TAG, "Unable to read $BRIDGE_ASSET", it)
            null
        }
    }

    private fun handleRawMessage(raw: String) {
        val envelope = BridgeProtocol.decodeEnvelope(raw) ?: run {
            Log.w(TAG, "Malformed envelope: ${raw.take(200)}")
            return
        }
        if (envelope.type == "CHAT_LOADED") {
            envelope.payload["chatId"]?.let { element ->
                runCatching { element.toString().trim('"') }.getOrNull()?.let { id ->
                    if (id.isNotEmpty()) chatId = id
                }
            }
        }
        val event = BridgeProtocol.eventFrom(envelope, chatId)
        when (event) {
            is EngineEvent.Ready -> _status.value = EngineStatus(
                phase = EnginePhase.READY,
                serverVersion = event.version,
                currentUser = event.currentUser
            )
            is EngineEvent.EngineError -> _status.value =
                _status.value.copy(phase = EnginePhase.ERROR, detail = event.message)
            else -> Unit
        }
        _events.tryEmit(event)
    }

    private fun originRule(url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val scheme = uri.scheme ?: "http"
        val host = uri.host ?: "127.0.0.1"
        val port = uri.port
        return if (port > 0) "$scheme://$host:$port" else "$scheme://$host"
    }
}
