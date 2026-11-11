package com.imperialreader.data.source

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.imperialreader.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Result of running a page in a hidden WebView: a stream request that was seen, or an iframe URL that appeared. */
internal class WebFound(val stream: ChapterPages?, val embedUrl: String?)

/**
 * Last-resort stream discovery for players that build their stream with JavaScript (this is what Aniyomi
 * extensions do with a WebView resolver). The page is loaded invisibly, every request it makes is inspected,
 * and the first .m3u8 / .mp4 request is returned together with the Referer/Origin the player used.
 *
 * With [clickLabel] it instead clicks the server button with that exact text (e.g. "DUB-1") and returns the
 * iframe URL the page switches to (or a stream request that follows the click).
 */
internal object WebViewStream {
    private val main = Handler(Looper.getMainLooper())

    private const val AUTOPLAY_JS =
        "(function(){try{var v=document.querySelector('video');if(v){v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){});}" +
            "['.jw-icon-display','.vjs-big-play-button','.plyr__control--overlaid','.play-button','#play','[aria-label=Play]','.jwplayer','#player']" +
            ".forEach(function(s){var e=document.querySelector(s);if(e){try{e.click();}catch(x){}}});}catch(e){}})();"

    private const val IFRAMES_JS =
        "(function(){var f=document.querySelectorAll('iframe');var r=[];" +
            "for(var i=0;i<f.length;i++){r.push(f[i].src||f[i].getAttribute('data-src')||'');}return JSON.stringify(r);})();"

    private fun clickJs(label: String): String =
        "(function(){var want=" + JSONObject.quote(label) + ".toLowerCase();" +
            "var els=document.querySelectorAll('a,button,div,span,li,label');" +
            "for(var i=0;i<els.length;i++){var e=els[i];" +
            "if(e.children.length===0&&(e.textContent||'').trim().toLowerCase()===want){e.click();return true;}}return false;})();"

    private fun isStream(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        if (path.endsWith(".m3u8")) return true
        if (!path.endsWith(".mp4")) return false
        val name = path.substringAfterLast('/')
        return listOf("ad", "ads", "promo", "banner", "preroll").none { name.startsWith(it) || "/$it/" in path }
    }

    private fun originOf(url: String): String =
        runCatching { URI(url).let { "${it.scheme}://${it.authority}" } }.getOrDefault(url)

    private fun parseUrls(raw: String?): List<String> = runCatching {
        val decoded = JSONTokener(raw ?: return emptyList()).nextValue() as? String ?: return emptyList()
        val array = JSONArray(decoded)
        (0 until array.length()).map { array.optString(it) }
    }.getOrDefault(emptyList())

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun load(
        pageUrl: String,
        referer: String,
        clickLabel: String? = null,
        timeoutMs: Long = 25_000
    ): WebFound? = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<WebFound?> { cont ->
                val app = App.instance
                val ua = app.network.userAgent
                val wv = WebView(app)
                val done = AtomicBoolean(false)
                val clicked = AtomicBoolean(clickLabel == null)
                var started = false
                var initial: List<String> = emptyList()
                val pageHost = runCatching { URI(pageUrl).host }.getOrNull()

                fun destroy() = main.post { runCatching { wv.stopLoading(); wv.destroy() } }
                fun finish(result: WebFound?) {
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
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = ua
                    blockNetworkImage = true
                    javaScriptCanOpenWindowsAutomatically = false
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                fun poll(attempt: Int) {
                    if (done.get()) return
                    wv.evaluateJavascript(IFRAMES_JS) { raw ->
                        val changed = parseUrls(raw).firstOrNull { it.startsWith("http") && it !in initial }
                        when {
                            changed != null -> finish(WebFound(null, changed))
                            attempt >= 12 -> finish(null)
                            else -> main.postDelayed({ poll(attempt + 1) }, 700)
                        }
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url.toString()
                        if (clicked.get() && isStream(url)) {
                            val headers = request.requestHeaders
                            val ref = headers["Referer"] ?: headers["referer"] ?: pageUrl
                            val origin = headers["Origin"] ?: headers["origin"] ?: originOf(ref)
                            finish(
                                WebFound(
                                    ChapterPages(listOf(url), mapOf("Referer" to ref, "Origin" to origin, "User-Agent" to ua)),
                                    null
                                )
                            )
                        }
                        return null
                    }

                    // keep ad redirects away from the hidden page
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.host != pageHost

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (started) return
                        started = true
                        if (clickLabel == null) {
                            listOf(0L, 1500L, 4000L, 8000L).forEach { delay ->
                                main.postDelayed({ if (!done.get()) view.evaluateJavascript(AUTOPLAY_JS, null) }, delay)
                            }
                        } else {
                            main.postDelayed({
                                if (done.get()) return@postDelayed
                                view.evaluateJavascript(IFRAMES_JS) { before ->
                                    initial = parseUrls(before)
                                    clicked.set(true)
                                    view.evaluateJavascript(clickJs(clickLabel)) { hit ->
                                        if (hit == "true") poll(0) else finish(null)
                                    }
                                }
                            }, 1500)
                        }
                    }
                }
                wv.loadUrl(pageUrl, mapOf("Referer" to referer))
            }
        }
    }
}
