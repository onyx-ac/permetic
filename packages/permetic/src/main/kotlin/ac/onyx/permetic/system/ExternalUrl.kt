package ac.onyx.permetic.system

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * `openUrl` hands a web-app-supplied string to `Intent.ACTION_VIEW`, which is an
 * implicit intent — so the string is an attack surface, not just a URL. `intent://`
 * can reach arbitrary components in other apps, `file://` can hand a local file to
 * whatever claims it, and `javascript:` is meaningless outside a browser. Only
 * `http`/`https` are ever forwarded, and only with a real host.
 *
 * Kept as a pure function with no Android types so the allowlist is unit-testable on
 * the JVM, the same reasoning as the transport layer's `isOriginAllowed` (spec 01,
 * task 3).
 */
@Suppress("SwallowedException")
internal fun isExternalUrlAllowed(url: String): Boolean {
    val parsed =
        try {
            URI(url)
        } catch (e: URISyntaxException) {
            // "Unparseable" and "not allowed" are the same outcome to every caller,
            // so the parse detail has nowhere to go and is deliberately dropped.
            return false
        }
    val scheme = parsed.scheme?.lowercase(Locale.ROOT)
    // The host check rejects "http://" and scheme-relative junk that parses fine but
    // points nowhere.
    return scheme in ALLOWED_SCHEMES && !parsed.host.isNullOrBlank()
}

private val ALLOWED_SCHEMES = setOf("http", "https")
