package ac.onyx.permetic

import ac.onyx.permetic.ota.android.SubfolderAssetsPathHandler
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.File

/**
 * Serves the web app over `https://appassets.androidplatform.net`. Never `file://` —
 * see spec 01's non-negotiables.
 *
 * **One handler, chosen up front**, which is spec 01's "a resolver decides which
 * directory is live" (task 10). [liveBundle] is whatever `OtaBundleStore.resolve()`
 * returned at startup: non-null and the OTA'd tree is served from internal storage,
 * null and the bundle shipped in the APK is served instead. Deliberately not a
 * fallback chain across both sources — a bundle is verified as a complete tree against
 * its manifest, so quietly filling a gap in it from another source would serve a mix
 * that nothing ever vouched for.
 *
 * Navigation policy (external links opening in the browser, spec 01 task 11) isn't
 * implemented yet; the default `WebViewClient` behaviour applies.
 */
public class PermeticWebViewClient(
    context: Context,
    assetsSubfolder: String = "",
    liveBundle: File? = null,
) : WebViewClient() {
    private val assetLoader =
        WebViewAssetLoader.Builder()
            .addPathHandler("/", pathHandler(context, assetsSubfolder, liveBundle))
            .build()

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

    private companion object {
        fun pathHandler(
            context: Context,
            assetsSubfolder: String,
            liveBundle: File?,
        ): WebViewAssetLoader.PathHandler =
            if (liveBundle != null) {
                WebViewAssetLoader.InternalStoragePathHandler(context, liveBundle)
            } else {
                SubfolderAssetsPathHandler(context, assetsSubfolder)
            }
    }
}
