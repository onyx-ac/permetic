package ac.onyx.permetic.ota.android

import ac.onyx.permetic.ota.isSafeRelativePath
import android.content.Context
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader

/**
 * Serves the bundled web app from a subfolder of `assets/`, which spec 01's builder
 * example (`.assets("web") // src/main/assets/web`) has always implied but
 * `AssetsPathHandler` cannot do on its own — its only public constructor takes a bare
 * `Context` and reads from the assets root (verified against androidx.webkit 1.12.1,
 * not assumed).
 *
 * This prepends the subfolder and **delegates**, rather than reimplementing asset
 * lookup. `AssetsPathHandler` owns the parts worth getting right — MIME-type
 * detection, `index.html` resolution, and containment within `assets/` — and
 * hand-rolling those was explicitly declined in task 5. The extra path check here is
 * defence in depth on top of that, not the only line of defence.
 */
public class SubfolderAssetsPathHandler(
    context: Context,
    subfolder: String,
) : WebViewAssetLoader.PathHandler {
    private val delegate = WebViewAssetLoader.AssetsPathHandler(context)
    private val prefix = subfolder.trim('/').let { if (it.isEmpty()) "" else "$it/" }

    override fun handle(path: String): WebResourceResponse? {
        val relative = path.removePrefix("/")
        // An empty suffix is the directory request for the origin root, which the
        // delegate resolves to index.html; anything else must be an ordinary
        // relative path.
        if (relative.isNotEmpty() && !isSafeRelativePath(relative)) return null
        return delegate.handle(prefix + relative)
    }
}
