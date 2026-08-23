package ac.onyx.permetic.transport

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks [BridgeRequest]s native is still processing, keyed by correlation id.
 * [cancelAll] resolves every still-pending entry as a [BridgeErrorCode.CANCELLED]
 * [BridgeResponse.Failure] rather than dropping it — spec 01's lifecycle rule:
 * in-flight requests are cancelled when the WebView is destroyed, never silently
 * dropped. [ConcurrentHashMap] gives thread-safe registration from the dispatch
 * thread and resolution from a WebView callback thread without explicit locking.
 */
public class PendingRequestTable {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<BridgeResponse>>()

    public fun register(id: String): CompletableDeferred<BridgeResponse> {
        val deferred = CompletableDeferred<BridgeResponse>()
        pending[id] = deferred
        return deferred
    }

    public fun resolve(
        id: String,
        response: BridgeResponse,
    ) {
        pending.remove(id)?.complete(response)
    }

    public fun cancelAll(cause: BridgeError) {
        val ids = pending.keys.toList()
        for (id in ids) {
            pending.remove(
                id,
            )?.complete(BridgeResponse.Failure(v = CONTRACT_VERSION, id = id, error = cause))
        }
    }

    public fun size(): Int = pending.size
}
