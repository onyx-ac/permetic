package ac.onyx.permetic

import ac.onyx.permetic.capability.AppLifecycleState
import ac.onyx.permetic.capability.HostKind
import ac.onyx.permetic.capability.LogLevel
import ac.onyx.permetic.capability.SharePayload
import ac.onyx.permetic.capability.SystemCapability
import ac.onyx.permetic.capability.SystemInfo
import ac.onyx.permetic.transport.android.TestActivity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private class FakeSystemCapability : SystemCapability {
    override suspend fun info(): SystemInfo =
        SystemInfo(HostKind.WEBVIEW, "1.0", 1, 34, "en-US", AppLifecycleState.FOREGROUND)

    override fun log(
        level: LogLevel,
        message: String,
    ) {}

    override suspend fun share(payload: SharePayload) {}

    override suspend fun openUrl(url: String) {}

    override fun onLifecycle(): Flow<AppLifecycleState> = emptyFlow()
}

/**
 * End-to-end check against a real WebView, real Activity, and a real registered
 * capability — exercises `attach()` -> `WebViewCarrier` -> `trackedDispatch` ->
 * `PendingRequestTable` -> `Dispatcher` -> registry lookup -> the capability's own
 * method -> encode -> reply, all the way back to JS. Reuses the same fixture page as
 * `WebViewCarrierInstrumentedTest` (spec 01 task 3) since the wire shape is
 * identical; only what's registered natively differs.
 *
 * NEEDS A DEVICE OR EMULATOR. See `WebViewCarrierInstrumentedTest`'s KDoc for the
 * same caveat — written and compile-checked here, run separately when a
 * device/emulator is available.
 */
@RunWith(AndroidJUnit4::class)
public class PermeticControllerInstrumentedTest {
    private class ReplyProbe(private val deliver: (String) -> Unit) {
        @JavascriptInterface
        fun onReply(json: String) = deliver(json)
    }

    @Test
    public fun attachedControllerDispatchesToARegisteredCapabilityAndRepliesToJs() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))

        val allowedOrigin = "https://example.com"
        val fixtureHtml =
            InstrumentationRegistry.getInstrumentation()
                .context.assets.open("fixture.html").bufferedReader().readText()
        val replyLatch = CountDownLatch(1)
        var replyText: String? = null

        ActivityScenario.launch(TestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller =
                    PermeticController.Builder(activity)
                        .allowOrigin(allowedOrigin)
                        .capability(FakeSystemCapability())
                        .build()

                val webView = WebView(activity)
                webView.settings.javaScriptEnabled = true
                webView.addJavascriptInterface(
                    ReplyProbe { json ->
                        replyText = json
                        replyLatch.countDown()
                    },
                    "TestProbe",
                )

                controller.attach(webView)
                webView.loadDataWithBaseURL(allowedOrigin, fixtureHtml, "text/html", "utf-8", null)
            }

            assertTrue("no reply received within 5s", replyLatch.await(5, TimeUnit.SECONDS))
            val response: JsonObject = Json.parseToJsonElement(requireNotNull(replyText)).jsonObject
            assertEquals(JsonPrimitive(true), response["ok"])
            val value = response.getValue("value").jsonObject
            assertEquals(JsonPrimitive("1.0"), value["appVersion"])
        }
    }
}
