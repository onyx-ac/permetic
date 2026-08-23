package ac.onyx.permetic

import ac.onyx.permetic.capability.PermeticCapability
import ac.onyx.permetic.internal.CapabilityRegistry
import ac.onyx.permetic.internal.Dispatcher
import ac.onyx.permetic.transport.BridgeError
import ac.onyx.permetic.transport.BridgeErrorCode
import ac.onyx.permetic.transport.BridgeEvent
import ac.onyx.permetic.transport.BridgeRequest
import ac.onyx.permetic.transport.BridgeResponse
import ac.onyx.permetic.transport.PendingRequestTable
import ac.onyx.permetic.transport.android.JavascriptInterfaceFallback
import ac.onyx.permetic.transport.android.WebViewCarrier
import android.app.Activity
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Public entry point. A WebView that runs the web app and grants it scoped,
 * declared access to native features — nothing is permitted that wasn't registered
 * (see [CapabilityRegistry.isAvailable] and the [Dispatcher]'s `UNAVAILABLE`
 * fallback for anything that wasn't).
 *
 * Two coroutine scopes, deliberately kept separate: [workScope] runs the actual
 * dispatch work (capability calls, subscription `Flow` collection) and is cancelled
 * on [onDestroy]; [carrierScope] runs [WebViewCarrier]'s "await a reply, then send
 * it" coroutines and is **not** cancelled. If both used the same scope, cancelling
 * it on teardown would kill the very coroutines responsible for sending back the
 * `CANCELLED` reply spec 01 requires — see [trackedDispatch].
 *
 * Keeping the same [PermeticController] instance alive across a configuration
 * change (e.g. in a retained ViewModel) and calling [attach] again with the new
 * `WebView` is the host app's responsibility. Subscriptions are owned by this
 * controller, not the `WebView`: they're re-attached, not recreated — the
 * Kotlin-side `Flow` collectors in [workScope] never stopped, only the delivery
 * sink ([WebViewCarrier]'s retained reply proxy) changes once the new `WebView`
 * sends its first request.
 */
public class PermeticController private constructor(
    activity: Activity,
    private val allowedOrigins: Set<String>,
    private val registry: CapabilityRegistry,
) {
    private val activityRef = WeakReference(activity)
    private val pending = PendingRequestTable()
    private val workJob = SupervisorJob()
    private val workScope = CoroutineScope(workJob + Dispatchers.Default)
    private val carrierScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var carrier: WebViewCarrier
    private val dispatcher =
        Dispatcher(registry, workScope) { event: BridgeEvent -> carrier.pushEvent(event) }

    private var attachedWebView: WebView? = null

    init {
        carrier = WebViewCarrier(allowedOrigins, carrierScope, ::trackedDispatch)
    }

    /** Non-null only while the [Activity] that built this controller is alive. */
    public fun activity(): Activity? = activityRef.get()

    /**
     * Registers the transport against [webView] — [WebViewCompat.addWebMessageListener]
     * when supported, the [JavascriptInterfaceFallback] otherwise — and serves the
     * bundled web app via [PermeticWebViewClient]. Safe to call again with a new
     * `WebView` after a configuration change; existing subscriptions keep running.
     */
    public fun attach(webView: WebView) {
        attachedWebView = webView
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, JS_OBJECT_NAME, allowedOrigins, carrier)
        } else {
            webView.addJavascriptInterface(
                JavascriptInterfaceFallback(::trackedDispatch),
                JS_OBJECT_NAME,
            )
        }
        webView.webViewClient = PermeticWebViewClient(webView.context)
    }

    /**
     * Cancels in-flight requests (they resolve `CANCELLED`, never dropped — spec 01's
     * lifecycle rule), cancels every running subscription, clears the [Activity]
     * reference deterministically, and detaches from the current `WebView` if any.
     * The host app must call this (e.g. from `Activity.onDestroy()` or a retained
     * ViewModel's `onCleared()`) — nothing here wires it up automatically yet.
     */
    public fun onDestroy() {
        activityRef.clear()
        attachedWebView?.let {
                webView ->
            WebViewCompat.removeWebMessageListener(webView, JS_OBJECT_NAME)
        }
        attachedWebView = null
        pending.cancelAll(
            BridgeError(code = BridgeErrorCode.CANCELLED, message = "PermeticController destroyed"),
        )
        dispatcher.cancelAllSubscriptions()
        workJob.cancel()
    }

    /**
     * Registers the request in [pending] before doing any real work, and hands the
     * work itself to [workScope] — a separate coroutine from the one awaiting the
     * result here. If [onDestroy] resolves [pending] as `CANCELLED` before the real
     * work finishes, this `await()` returns that value immediately regardless of
     * what happens to the (by-then-cancelled) `workScope` coroutine.
     */
    private suspend fun trackedDispatch(request: BridgeRequest): BridgeResponse {
        val deferred = pending.register(request.id)
        workScope.launch {
            val response = dispatcher.dispatch(request)
            pending.resolve(request.id, response)
        }
        return deferred.await()
    }

    public class Builder(private val activity: Activity) {
        private val allowedOrigins = mutableSetOf<String>()
        private val registry = CapabilityRegistry()

        public fun allowOrigin(origin: String): Builder = apply { allowedOrigins.add(origin) }

        /**
         * Accepted to match spec 01's builder shape; currently unused. See
         * [PermeticWebViewClient]'s KDoc — androidx.webkit 1.12.1's
         * `AssetsPathHandler` has no subfolder parameter, so this doesn't yet change
         * where assets are read from.
         */
        @Suppress("UnusedParameter")
        public fun assets(path: String): Builder = apply { /* not yet wired - see KDoc */ }

        public fun capability(capability: PermeticCapability): Builder =
            apply {
                registry.register(capability)
            }

        public fun build(): PermeticController =
            PermeticController(
                activity,
                allowedOrigins.toSet(),
                registry,
            )
    }

    private companion object {
        const val JS_OBJECT_NAME = "PermeticNative"
    }
}
