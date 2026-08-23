package ac.onyx.permetic.transport.android

import android.webkit.WebView
import androidx.webkit.WebViewCompat

/**
 * Thin wrapper around `WebViewCompat.addDocumentStartJavaScript`. [scriptSource] is
 * supplied by the caller — the `permetic-web` runtime bundle, wired up when
 * `PermeticController` assembles a WebView (spec 01 task 5) — rather than hardcoded
 * here: this class's only job is the ordering guarantee spec 01 asks for (running
 * before any app script, including inline `<head>` scripts, which plain `<script>`
 * tag loading does not guarantee), not deciding what the bootstrap content is.
 *
 * [scriptSource] is a fixed resource, never built from request-time values — per
 * root CLAUDE.md, `evaluate("Foo.put('$id')")`-style JS string interpolation is
 * banned. Everything that needs to cross at request time goes through the
 * [ac.onyx.permetic.transport.BridgeRequest] envelope instead.
 */
public class BootstrapScript(
    private val scriptSource: String,
    private val allowedOriginRules: Set<String>,
) {
    public fun installOn(webView: WebView) {
        WebViewCompat.addDocumentStartJavaScript(webView, scriptSource, allowedOriginRules)
    }
}
