package ac.onyx.permetic.pip

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Measures the one thing ADR 0011 says the activity-tracker use case lives or dies on:
 * **does JavaScript keep running in a WebView whose Activity is in Picture-in-Picture?**
 *
 * For a `<video>` this barely matters — decode continues in the media pipeline with JS
 * nowhere in the per-frame loop. A canvas stream has no native producer: every frame is
 * a JS paint, so if timers stop, the PiP window shows a frozen clock, which is worse
 * than showing nothing because it looks like it is working.
 *
 * **Self-driving on purpose.** Driving this from `ActivityScenario` did not work on the
 * API 36 emulator: the activity never gained window focus and the system refused PiP
 * ten times out of ten. Launched normally it behaves like a real app. Run it with:
 *
 * ```
 * adb shell am start -n ac.onyx.permetic.test/ac.onyx.permetic.pip.PipProbeActivity
 * adb logcat -d -s PipProbe:I
 * ```
 *
 * Counts two independent JS clocks so "stopped" can be told from "throttled": a 100ms
 * `setInterval` and a `requestAnimationFrame` loop. Also declared with the three
 * manifest attributes spec 07 requires, so it doubles as a worked example of them.
 */
public class PipProbeActivity : Activity() {
    private val intervalTicks = AtomicInteger()
    private val rafTicks = AtomicInteger()
    private val started = AtomicInteger()

    @Volatile
    private var visibility: String = "?"

    private inner class Probe {
        @JavascriptInterface
        fun interval(state: String) {
            visibility = state
            intervalTicks.incrementAndGet()
        }

        @JavascriptInterface
        fun raf() {
            rafTicks.incrementAndGet()
        }

        @JavascriptInterface
        fun ready() {
            if (started.compareAndSet(0, 1)) runProbe()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.addJavascriptInterface(Probe(), "Probe")
        setContentView(webView)
        webView.loadDataWithBaseURL("https://probe.example", PAGE, "text/html", "utf-8", null)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Log.i(TAG, "onPictureInPictureModeChanged -> $isInPictureInPictureMode")
    }

    private fun runProbe() {
        thread(name = "pip-probe") {
            val foreground = sample("foreground")

            val main = Handler(Looper.getMainLooper())
            val entered = AtomicInteger(-1)
            main.post {
                val ok =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        enterPictureInPictureMode(PictureInPictureParams.Builder().build())
                    } else {
                        false
                    }
                entered.set(if (ok) 1 else 0)
            }
            while (entered.get() == -1) Thread.sleep(POLL_MILLIS)
            Log.i(TAG, "enterPictureInPictureMode -> ${entered.get() == 1}")
            if (entered.get() != 1) {
                Log.i(TAG, "=== RESULT: could not enter PiP, nothing measured ===")
                return@thread
            }

            Thread.sleep(SETTLE_MILLIS)
            Log.i(TAG, "isInPictureInPictureMode = $isInPictureInPictureMode")

            // Sampled repeatedly rather than once: Chromium's background throttling is
            // staged and delayed (aggressive after ~10s, intensive after minutes), so a
            // single short window right after the transition would miss it entirely.
            val samples = (1..PIP_SAMPLES).map { sample("pip t+${it * WINDOW_MILLIS / 1000}s") }
            val inPip = samples.last()
            val worstInterval = samples.minOf { it.first }
            val worstRaf = samples.minOf { it.second }

            Log.i(TAG, "=== RESULT ===")
            Log.i(
                TAG,
                "foreground:  interval=%.1f/s raf=%.1f/s"
                    .format(foreground.first, foreground.second),
            )
            Log.i(TAG, "PiP (last):  interval=%.1f/s raf=%.1f/s".format(inPip.first, inPip.second))
            Log.i(TAG, "PiP (worst): interval=%.1f/s raf=%.1f/s".format(worstInterval, worstRaf))
            Log.i(TAG, "document.visibilityState while in PiP = $visibility")
            Log.i(TAG, "interval: " + verdict(foreground.first, worstInterval))
            Log.i(TAG, "raf:      " + verdict(foreground.second, worstRaf))
        }
    }

    /** Ticks per second over [WINDOW_MILLIS], as (interval, rAF). */
    private fun sample(label: String): Pair<Double, Double> {
        val startInterval = intervalTicks.get()
        val startRaf = rafTicks.get()
        Thread.sleep(WINDOW_MILLIS)
        val seconds = WINDOW_MILLIS / 1000.0
        val interval = (intervalTicks.get() - startInterval) / seconds
        val raf = (rafTicks.get() - startRaf) / seconds
        Log.i(TAG, "$label: interval=%.1f/s raf=%.1f/s".format(interval, raf))
        return interval to raf
    }

    private fun verdict(
        foreground: Double,
        inPip: Double,
    ): String =
        when {
            inPip < STOPPED_BELOW -> "STOPPED"
            inPip < foreground * THROTTLED_FRACTION -> "THROTTLED to %.1f/s".format(inPip)
            else -> "RUNNING at %.1f/s".format(inPip)
        }

    private companion object {
        const val TAG = "PipProbe"
        const val WINDOW_MILLIS = 4_000L
        const val SETTLE_MILLIS = 1_500L
        const val POLL_MILLIS = 100L
        const val STOPPED_BELOW = 0.5
        const val THROTTLED_FRACTION = 0.5
        const val PIP_SAMPLES = 12

        const val PAGE = """
            <!DOCTYPE html><html><body>
            <canvas id="c" width="320" height="180"></canvas>
            <script>
              // Mirrors what an activity tracker actually does: every frame is a JS
              // paint, with no native producer behind it (ADR 0011).
              var ctx = document.getElementById('c').getContext('2d');
              var n = 0;
              setInterval(function () {
                n++;
                ctx.fillStyle = '#123456';
                ctx.fillRect(0, 0, 320, 180);
                ctx.fillStyle = '#ffffff';
                ctx.font = '32px sans-serif';
                ctx.fillText(String(n), 20, 100);
                Probe.interval(document.visibilityState);
              }, 100);
              function loop() { Probe.raf(); requestAnimationFrame(loop); }
              requestAnimationFrame(loop);
              Probe.ready();
            </script>
            </body></html>
        """
    }
}
