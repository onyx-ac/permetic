package ac.onyx.permetic.transport.android

import ac.onyx.permetic.transport.BridgeResponse
import ac.onyx.permetic.transport.BridgeResponseSerializer
import ac.onyx.permetic.transport.CONTRACT_VERSION
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end check against a real WebView and the fixture page in
 * `androidTest/assets/fixture.html`: exercises [WebViewCarrier] through the real
 * `WebMessageListener` callback, not a mocked `permetic` object — per spec 01's
 * verification section.
 *
 * NEEDS A DEVICE OR EMULATOR. Not run as part of `:permetic:test` and not wired into
 * CI yet (no emulator runner configured — see `.github/workflows/ci.yml`). Written
 * and compile-checked (`:permetic:compileDebugAndroidTestKotlin`) so it's reviewable
 * now; run it with `./gradlew :permetic:connectedAndroidTest` on a real device before
 * this step is considered fully verified.
 */
@RunWith(AndroidJUnit4::class)
public class WebViewCarrierInstrumentedTest {
    private class TestProbe(private val deliver: (String) -> Unit) {
        @JavascriptInterface
        fun onReply(json: String) = deliver(json)
    }

    @Test
    public fun dispatchesAnAllowedOriginRequestAndDeliversTheReplyBackToJs() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))

        val allowedOrigin = "https://example.com"
        val fixtureHtml =
            InstrumentationRegistry.getInstrumentation()
                .context.assets.open("fixture.html").bufferedReader().readText()
        val replyLatch = CountDownLatch(1)
        var replyText: String? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        ActivityScenario.launch(TestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val webView = WebView(activity)
                webView.settings.javaScriptEnabled = true
                webView.addJavascriptInterface(
                    TestProbe { json ->
                        replyText = json
                        replyLatch.countDown()
                    },
                    "TestProbe",
                )

                val carrier =
                    WebViewCarrier(
                        allowedOrigins = setOf(allowedOrigin),
                        scope = scope,
                    ) { request ->
                        BridgeResponse.Success(
                            v = CONTRACT_VERSION,
                            id = request.id,
                            value = JsonPrimitive("ok"),
                        )
                    }
                WebViewCompat.addWebMessageListener(
                    webView,
                    "PermeticNative",
                    setOf(allowedOrigin),
                    carrier,
                )

                webView.webViewClient = WebViewClient()
                webView.loadDataWithBaseURL(allowedOrigin, fixtureHtml, "text/html", "utf-8", null)
            }

            assertTrue(
                "no reply received from the carrier within 5s",
                replyLatch.await(5, TimeUnit.SECONDS),
            )
            val response =
                Json.decodeFromString(
                    BridgeResponseSerializer,
                    requireNotNull(replyText),
                )
            assertTrue(response is BridgeResponse.Success)
            assertEquals("itest-1", response.id)
            assertEquals(JsonPrimitive("ok"), (response as BridgeResponse.Success).value)
        }
    }
}
