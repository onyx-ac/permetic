package ac.onyx.permetic

import ac.onyx.permetic.capability.PermeticCapability
import ac.onyx.permetic.internal.CapabilityRegistry
import ac.onyx.permetic.internal.Dispatcher
import ac.onyx.permetic.ota.OtaBundleStore
import ac.onyx.permetic.ota.android.SubfolderAssetsPathHandler
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
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

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
    private val assetsSubfolder: String,
    private val ota: OtaBundleStore?,
    private val liveBundle: File?,
) {
    private val bootConfirmed = AtomicBoolean(false)

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
        webView.webViewClient =
            PermeticWebViewClient(webView.context, assetsSubfolder, liveBundle)
    }

    /**
     * Confirms the currently-served OTA bundle actually runs, so it becomes the
     * rollback target (see [OtaBundleStore.markBootSuccessful]). No-op without OTA
     * configured, and after the first call.
     *
     * [trackedDispatch] calls this on the first successful bridge request, which is a
     * genuine liveness signal — the JS bundle parsed, the `permetic-web` runtime
     * initialised, and it reached native — and unlike a page-load callback it is not
     * something a white-screening bundle also produces. Call it explicitly instead if
     * the web app has a stronger "I am actually working" moment to report.
     */
    public suspend fun markWebAppReady() {
        val store = ota ?: return
        if (!bootConfirmed.compareAndSet(false, true)) return
        withContext(Dispatchers.IO) { store.markBootSuccessful() }
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
        val response = deferred.await()
        if (response is BridgeResponse.Success) markWebAppReady()
        return response
    }

    public class Builder(private val activity: Activity) {
        private val allowedOrigins = mutableSetOf<String>()
        private val registry = CapabilityRegistry()
        private var assetsSubfolder: String = ""
        private var ota: OtaBundleStore? = null

        public fun allowOrigin(origin: String): Builder = apply { allowedOrigins.add(origin) }

        /**
         * Subfolder of `src/main/assets/` holding the bundled web app, e.g.
         * `assets("web")`. Empty (the default) serves from the assets root. Honoured
         * as of task 10 via [SubfolderAssetsPathHandler]; it was accepted and ignored
         * before that.
         */
        public fun assets(path: String): Builder = apply { assetsSubfolder = path }

        /**
         * Enables OTA. The live directory is chosen **here**, at build time, not at
         * [attach] — so a bundle installed while the app runs applies on the next
         * launch and a running session never changes underneath itself (spec 01, D-5).
         */
        public fun ota(store: OtaBundleStore): Builder = apply { ota = store }

        public fun capability(capability: PermeticCapability): Builder =
            apply {
                registry.register(capability)
            }

        public fun build(): PermeticController =
            PermeticController(
                activity,
                allowedOrigins.toSet(),
                registry,
                assetsSubfolder,
                ota,
                ota?.resolve(),
            )
    }

    private companion object {
        const val JS_OBJECT_NAME = "PermeticNative"
    }
}
