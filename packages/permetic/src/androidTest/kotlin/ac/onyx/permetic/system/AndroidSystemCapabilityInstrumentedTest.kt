package ac.onyx.permetic.system

import ac.onyx.permetic.PermeticController
import ac.onyx.permetic.capability.AppLifecycleState
import ac.onyx.permetic.capability.CapabilityException
import ac.onyx.permetic.capability.HostKind
import ac.onyx.permetic.transport.BridgeErrorCode
import ac.onyx.permetic.transport.android.TestActivity
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import kotlin.test.assertFailsWith

/**
 * The real `system` capability against a real device (spec 01, task 6). Everything
 * here needs a live `Context`, `PackageManager` or `ProcessLifecycleOwner`, so none of
 * it is reachable from the JVM test source set.
 *
 * NEEDS A DEVICE OR EMULATOR.
 */
@RunWith(AndroidJUnit4::class)
public class AndroidSystemCapabilityInstrumentedTest {
    private class ReplyProbe(private val deliver: (String) -> Unit) {
        @JavascriptInterface
        fun onReply(json: String) = deliver(json)
    }

    @Test
    public fun infoReportsRealDeviceAndPackageState() {
        lateinit var capability: AndroidSystemCapability
        ActivityScenario.launch(TestActivity::class.java).use { scenario ->
            scenario.onActivity { capability = AndroidSystemCapability(it) }

            val info = runBlocking { capability.info() }

            assertEquals(HostKind.WEBVIEW, info.host)
            assertEquals(Build.VERSION.SDK_INT, info.osVersion)
            assertTrue(
                "locale should be a real tag, was '${info.locale}'",
                info.locale.isNotBlank(),
            )
            assertEquals(AppLifecycleState.FOREGROUND, info.lifecycle)
        }
    }

    @Test
    public fun onLifecycleEmitsCurrentStateWithoutWaitingForATransition() {
        lateinit var capability: AndroidSystemCapability
        ActivityScenario.launch(TestActivity::class.java).use { scenario ->
            scenario.onActivity { capability = AndroidSystemCapability(it) }

            val first =
                runBlocking {
                    withTimeout(TIMEOUT_MILLIS) { capability.onLifecycle().first() }
                }

            assertEquals(AppLifecycleState.FOREGROUND, first)
        }
    }

    @Test
    public fun openUrlRefusesASchemeThatCouldReachAnotherAppsComponents() {
        lateinit var capability: AndroidSystemCapability
        ActivityScenario.launch(TestActivity::class.java).use { scenario ->
            scenario.onActivity { capability = AndroidSystemCapability(it) }

            val failure =
                assertFailsWith<CapabilityException> {
                    runBlocking { capability.openUrl("intent://scan/#Intent;scheme=zxing;end") }
                }

            assertEquals(BridgeErrorCode.INVALID_ARGUMENT, failure.code)
        }
    }

    /**
     * The full path spec 01's verification section asks for, with nothing faked on
     * either end: real JS in the fixture page -> real `WebMessageListener` ->
     * `PermeticController` -> `Dispatcher` -> the real [AndroidSystemCapability] ->
     * real `PackageManager`/`ProcessLifecycleOwner` -> back to JS.
     */
    @Test
    public fun theRealCapabilityAnswersTheRealGlobalEndToEnd() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))

        val allowedOrigin = "https://example.com"
        val fixtureHtml =
            InstrumentationRegistry.getInstrumentation()
                .context.assets.open("fixture.html").bufferedReader().readText()
        val replyLatch = CountDownLatch(1)
        var replyText: String? = null

        ActivityScenario.launch(TestActivity::class.java).use {
            it.onActivity { activity ->
                val controller =
                    PermeticController.Builder(activity)
                        .allowOrigin(allowedOrigin)
                        .capability(AndroidSystemCapability(activity))
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
            assertEquals(JsonPrimitive("webview"), value["host"])
            assertEquals(JsonPrimitive(Build.VERSION.SDK_INT), value["osVersion"])
            assertEquals(JsonPrimitive("foreground"), value["lifecycle"])
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
