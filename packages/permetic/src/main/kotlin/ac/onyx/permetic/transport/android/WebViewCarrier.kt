package ac.onyx.permetic.transport.android

import ac.onyx.permetic.transport.BridgeRequest
import ac.onyx.permetic.transport.BridgeResponse
import ac.onyx.permetic.transport.EnvelopeCodec
import ac.onyx.permetic.transport.decodeErrorResponse
import ac.onyx.permetic.transport.isOriginAllowed
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

/**
 * The primary transport: `WebViewCompat.addWebMessageListener`. Spec 01's
 * non-negotiable — never `@JavascriptInterface` except as the feature-checked
 * fallback ([JavascriptInterfaceFallback]).
 *
 * [dispatch] is a plain suspend function, not a concrete dispatcher type: this class
 * has no dispatch logic of its own (ADR-0002 — "the adapter has no carrier branch in
 * it"), which keeps the transport buildable and unit-testable independently of the
 * capability registry (spec 01 task 5). [scope] runs dispatch off the WebView
 * callback thread, per root CLAUDE.md's threading rule.
 */
public class WebViewCarrier(
    private val allowedOrigins: Set<String>,
    private val scope: CoroutineScope,
    private val dispatch: suspend (BridgeRequest) -> BridgeResponse,
) : WebViewCompat.WebMessageListener {
    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        // Origin check first, unconditionally, before anything else is even parsed.
        if (!isOriginAllowed(sourceOrigin.toString(), allowedOrigins)) return
        val text = message.data ?: return

        scope.launch {
            val response =
                try {
                    dispatch(EnvelopeCodec.decodeRequest(text))
                } catch (error: SerializationException) {
                    decodeErrorResponse(text, error)
                }
            replyProxy.postMessage(EnvelopeCodec.encodeResponse(response))
        }
    }
}
