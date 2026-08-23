package ac.onyx.permetic

import android.app.Activity
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Isolated JVM units for `PermeticController`'s own lifecycle wiring — not a full
 * `attach()`-through-teardown integration test, since a real `WebView` throws in a
 * plain JVM unit test (no Robolectric — not an existing dependency, not worth
 * adding for this). The cancellation mechanisms `onDestroy()` orchestrates
 * ([ac.onyx.permetic.transport.PendingRequestTable.cancelAll],
 * [ac.onyx.permetic.internal.Dispatcher.cancelAllSubscriptions]) are already
 * covered by `PendingRequestTableTest` and `DispatcherTest`. See
 * `PermeticControllerInstrumentedTest` for the real `attach()`-to-teardown path.
 */
class PermeticControllerLifecycleTest {
    @Test
    fun `activity() is non-null after building, then null after onDestroy`() {
        val activity = Activity()
        val controller = PermeticController.Builder(activity).build()

        assertNotNull(controller.activity())
        controller.onDestroy()
        assertNull(controller.activity())
    }

    @Test
    fun `onDestroy is safe to call more than once`() {
        val controller = PermeticController.Builder(Activity()).build()

        controller.onDestroy()
        controller.onDestroy()

        assertNull(controller.activity())
    }
}
