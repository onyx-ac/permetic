package ac.onyx.permetic.transport

/**
 * The transport's one enforcement point for spec 01's origin allowlist: exact
 * scheme+host+port match against what the embedding app registered via
 * `PermeticController.Builder.allowOrigin(...)`. No wildcards — a caller wanting to
 * allow multiple origins registers each one explicitly, and an allowlist entry that
 * isn't a real origin (e.g. a literal `"*"`) simply never matches anything rather
 * than being interpreted as a glob.
 *
 * [origin] and each entry in [allowedOrigins] are compared after trimming a trailing
 * slash, since Android's `WebMessageListener` and app code disagree on whether a bare
 * origin (no path) carries one. Otherwise the comparison is exact and case-sensitive
 * as given: callers are expected to register origins in the canonical lowercase form
 * `WebViewAssetLoader` always presents.
 *
 * Kept as a pure function, JVM-only, so this logic is unit-testable without a real
 * WebView. `WebViewCarrier` in `transport/android/` calls it as the first thing it
 * does with every incoming message, before attempting to parse anything.
 */
public fun isOriginAllowed(
    origin: String,
    allowedOrigins: Set<String>,
): Boolean = allowedOrigins.any { it.trimEnd('/') == origin.trimEnd('/') }
