package ac.onyx.permetic.transport.android

import ac.onyx.permetic.transport.BridgeRequest
import ac.onyx.permetic.transport.BridgeResponse
import ac.onyx.permetic.transport.EnvelopeCodec
import ac.onyx.permetic.transport.decodeErrorResponse
import android.webkit.JavascriptInterface
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException

/**
 * The `@JavascriptInterface` fallback, used only when
 * `WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)` is false
 * on the device. Exposes exactly one method, per spec 01. Delegates to the same
 * [dispatch] function as [WebViewCarrier] — one dispatch path, two thin entry
 * adapters — so there is no second implementation of request handling to drift.
 *
 * Unlike [WebViewCarrier], this class cannot enforce an origin allowlist:
 * `@JavascriptInterface` gives native no origin information about the calling frame,
 * which is precisely why spec 01 treats it as a fallback rather than the primary
 * transport. The mitigation is elsewhere — the WebView only ever navigates within the
 * single `WebViewAssetLoader` origin (spec 01's hardening pass, task 11).
 *
 * `@JavascriptInterface` methods are invoked synchronously from JS's perspective, on
 * an Android-managed non-UI thread: JS blocks on `post()` until it returns. Bridging
 * into a suspend [dispatch] therefore genuinely requires [runBlocking] here — the one
 * sanctioned exception to root CLAUDE.md's "no runBlocking outside tests" rule,
 * forced by the synchronous nature of the `@JavascriptInterface` API itself, not a
 * shortcut around structured concurrency.
 */
public class JavascriptInterfaceFallback(
    private val dispatch: suspend (BridgeRequest) -> BridgeResponse,
) {
    @JavascriptInterface
    public fun post(json: String): String =
        runBlocking {
            val response =
                try {
                    dispatch(EnvelopeCodec.decodeRequest(json))
                } catch (error: SerializationException) {
                    decodeErrorResponse(json, error)
                }
            EnvelopeCodec.encodeResponse(response)
        }
}
