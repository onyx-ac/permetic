package ac.onyx.permetic.internal

import ac.onyx.permetic.capability.CapabilityName
import ac.onyx.permetic.transport.BridgeEvent
import ac.onyx.permetic.transport.SubscriptionIdAllocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns subscription id allocation and the `Flow`-collecting coroutines behind every
 * `onXxx` capability method. [start] allocates an id, launches a collector that
 * pushes each emission as a [BridgeEvent] via [emitEvent], and returns the id
 * immediately as the dispatch result — the collector keeps running in the
 * background afterward. [cancel] is what the reserved `"unsubscribe"` method
 * (established in the `permetic-web` runtime, spec 01 task 4) calls.
 */
internal class SubscriptionRunner(
    private val scope: CoroutineScope,
    private val emitEvent: (BridgeEvent) -> Unit,
) {
    private val allocator = SubscriptionIdAllocator()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun <T> start(
        capability: CapabilityName,
        flow: Flow<T>,
        encode: (T) -> JsonElement,
    ): JsonElement {
        val id = allocator.allocate()
        val job =
            scope.launch {
                flow.collect { value ->
                    emitEvent(
                        BridgeEvent(
                            capability = capability,
                            subscription = id,
                            payload = encode(value),
                        ),
                    )
                }
            }
        jobs[id] = job
        job.invokeOnCompletion { jobs.remove(id) }
        return JsonPrimitive(id)
    }

    /** No-op if [id] is unknown — an unsubscribe racing a subscription that already
     * completed/failed on its own is not an error. */
    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
    }

    /** Cancels every still-running subscription. Called on `PermeticController.onDestroy()`. */
    fun cancelAll() {
        jobs.keys.toList().forEach { jobs.remove(it)?.cancel() }
    }
}
