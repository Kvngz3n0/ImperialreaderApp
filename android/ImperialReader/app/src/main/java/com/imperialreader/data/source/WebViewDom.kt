package com.imperialreader.data.source

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.imperialreader.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Reads content that a site fills in with JavaScript (chapter lists, lazily loaded images): the page is opened in a
 * hidden WebView exactly like a normal browser visit, [readyJs] is polled until it returns true, then [resultJs]
 * is evaluated and its string result returned (null when the page never became ready).
 */
internal object WebViewDom {
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun evaluate(
        url: String,
        referer: String? = null,
        readyJs: String,
        resultJs: String,
        scroll: Boolean = false,
        timeoutMs: Long = 30_000
    ): String? = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<String?> { cont ->
                val app = App.instance
                val wv = WebView(app)
                val done = AtomicBoolean(false)
                var started = false
                val pageHost = runCatching { URI(url).host }.getOrNull()

                fun destroy() = main.post { runCatching { wv.stopLoading(); wv.destroy() } }
                fun finish(result: String?) {
                    if (!done.compareAndSet(false, true)) return
                    destroy()
                    if (cont.isActive) cont.resume(result)
                }
                cont.invokeOnCancellation {
                    done.set(true)
                    destroy()
                }

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = app.network.userAgent
                    blockNetworkImage = true // only the image URLs are needed, not the pixels
                    javaScriptCanOpenWindowsAutomatically = false
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                fun decode(raw: String?): String? = runCatching {
                    if (raw == null || raw == "null") null else JSONTokener(raw).nextValue() as? String
                }.getOrNull()

                fun poll(attempt: Int) {
                    if (done.get()) return
                    if (scroll) wv.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight);", null)
                    wv.evaluateJavascript(readyJs) { ready ->
                        when {
                            ready == "true" -> wv.evaluateJavascript(resultJs) { raw -> finish(decode(raw)) }
                            attempt >= 40 -> finish(null)
                            else -> main.postDelayed({ poll(attempt + 1) }, 700)
                        }
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.host != pageHost

                    override fun onPageFinished(view: WebView, finishedUrl: String?) {
                        if (started) return
                        started = true
                        poll(0)
                    }
                }
                if (referer != null) wv.loadUrl(url, mapOf("Referer" to referer)) else wv.loadUrl(url)
            }
        }
    }
}
