package ac.onyx.permetic.ota

import ac.onyx.permetic.PermeticController
import ac.onyx.permetic.ota.android.SubfolderAssetsPathHandler
import ac.onyx.permetic.transport.CONTRACT_VERSION
import ac.onyx.permetic.transport.android.TestActivity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The parts of task 10 the JVM tests cannot reach: the asset-loader path handlers, and
 * an OTA'd bundle actually being served to a real WebView.
 *
 * Signing here also runs against **Android's** security provider rather than the
 * desktop JVM's, which is a genuinely different implementation — a P-256 setup that
 * works under SunEC and not under Conscrypt would otherwise only surface on a device.
 *
 * NEEDS A DEVICE OR EMULATOR.
 */
@RunWith(AndroidJUnit4::class)
public class OtaServingInstrumentedTest {
    private class LoadProbe(private val deliver: (String) -> Unit) {
        @JavascriptInterface
        fun onLoaded(text: String) = deliver(text)
    }

    private val keyPair =
        KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

    private fun signedBundle(
        dir: File,
        version: Long,
        files: Map<String, String>,
    ): File {
        dir.mkdirs()
        val digests =
            files.mapValues { (path, content) ->
                File(dir, path).apply { parentFile?.mkdirs() }.writeText(content)
                MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }
        val manifest =
            """{"bundleVersion":$version,"contractVersion":$CONTRACT_VERSION,"files":{""" +
                digests.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" } +
                "}}"
        File(dir, "manifest.json").writeBytes(manifest.toByteArray())
        File(dir, "manifest.sig").writeBytes(
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(manifest.toByteArray())
                sign()
            },
        )
        return dir
    }

    @Test
    public fun subfolderHandlerServesFromTheConfiguredAssetsSubfolder() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val handler = SubfolderAssetsPathHandler(context, "web")

        val response = handler.handle("hello.txt")

        assertNotNull("expected assets/web/hello.txt to be served", response)
        assertEquals("hello from the assets subfolder", response!!.data.bufferedReader().readText().trim())
    }

    @Test
    public fun subfolderHandlerRefusesToClimbOutOfTheSubfolder() {
        val context = InstrumentationRegistry.getInstrumentation().context

        assertNull(SubfolderAssetsPathHandler(context, "web").handle("../fixture.html"))
    }

    /**
     * The whole task-10 chain against a real WebView: verify a signed bundle, install
     * it, resolve it at startup, serve it from internal storage over the asset-loader
     * origin, and run its JavaScript.
     */
    @Test
    public fun anInstalledBundleIsServedToARealWebView() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(appContext.filesDir, "ota-itest-${System.nanoTime()}")
        val store = OtaBundleStore(root, keyPair.public.encoded)

        val staging =
            signedBundle(
                File(root, "staging"),
                version = 1,
                files =
                    mapOf(
                        "index.html" to
                            "<html><body><script>" +
                            "window.LoadProbe.onLoaded('served-from-ota');" +
                            "</script></body></html>",
                    ),
            )
        assertEquals(1L, store.install(staging))

        val loaded = CountDownLatch(1)
        var seen: String? = null

        try {
            ActivityScenario.launch(TestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val controller =
                        PermeticController.Builder(activity)
                            .allowOrigin(ASSET_ORIGIN)
                            .ota(store)
                            .build()

                    val webView = WebView(activity)
                    webView.settings.javaScriptEnabled = true
                    webView.addJavascriptInterface(
                        LoadProbe { text ->
                            seen = text
                            loaded.countDown()
                        },
                        "LoadProbe",
                    )
                    controller.attach(webView)
                    webView.loadUrl("$ASSET_ORIGIN/index.html")
                }

                assertTrue("OTA bundle never loaded", loaded.await(10, TimeUnit.SECONDS))
                assertEquals("served-from-ota", seen)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val ASSET_ORIGIN = "https://appassets.androidplatform.net"
    }
}
