package ac.onyx.permetic

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * Serves the bundled web app over `https://appassets.androidplatform.net` via
 * `WebViewAssetLoader.AssetsPathHandler`. Never `file://` — see spec 01's
 * non-negotiables.
 *
 * `AssetsPathHandler`'s only public constructor takes a bare `Context`: it always
 * reads relative to the `assets/` root, with no subfolder parameter. Spec 01's
 * builder example (`.assets("web") // src/main/assets/web`) implies subfolder
 * support that this androidx.webkit version (1.12.1) does not have — verified
 * against the real class, not assumed. Hand-rolling a subfolder-aware replacement
 * risks getting traversal protection or MIME-type handling subtly wrong, so this
 * stays a direct wrapper around the battle-tested handler, serving from the assets
 * root. `Builder.assets(path)` still exists to match spec 01's example, but the path
 * isn't used by this handler yet — task 10's OTA resolver will need to replace this
 * with something subfolder/multi-source aware anyway.
 *
 * Navigation policy (external links opening in the browser, spec 01 task 11) isn't
 * implemented yet; the default `WebViewClient` behavior applies.
 */
public class PermeticWebViewClient(context: Context) : WebViewClient() {
    private val assetLoader =
        WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
}
