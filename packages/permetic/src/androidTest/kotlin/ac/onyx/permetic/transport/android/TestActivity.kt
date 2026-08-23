package ac.onyx.permetic.transport.android

import android.app.Activity

/**
 * Minimal launchable host for [WebViewCarrierInstrumentedTest]. `ActivityScenario`
 * needs a manifest-declared launcher activity to resolve — the bare framework
 * `Activity` class isn't one. This activity does nothing beyond existing; the test
 * builds and attaches its own [android.webkit.WebView] programmatically.
 */
public class TestActivity : Activity()
