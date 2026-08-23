package ac.onyx.permetic.capability

import ac.onyx.permetic.transport.BridgeError
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors `BackgroundJobSpec` in `index.d.ts`. Android floors periodic work at 15 minutes. */
@Serializable
public data class BackgroundJobSpec(
    val id: String,
    val everyMinutes: Int,
    val requiresNetwork: Boolean = false,
    val requiresCharging: Boolean = false,
    val input: Map<String, String>? = null,
)

@Serializable
public enum class BackgroundJobState {
    @SerialName("enqueued")
    ENQUEUED,

    @SerialName("running")
    RUNNING,

    @SerialName("succeeded")
    SUCCEEDED,

    @SerialName("failed")
    FAILED,

    @SerialName("cancelled")
    CANCELLED,
}

@Serializable
public data class BackgroundJobStatus(
    val id: String,
    val state: BackgroundJobState,
    val lastRunAt: Long? = null,
    val lastError: BridgeError? = null,
)

/** Mirrors `BackgroundCapability` in `index.d.ts`. Schedules the native sync worker. */
public interface BackgroundCapability {
    /** Re-registering the same [BackgroundJobSpec.id] replaces the schedule. */
    public suspend fun schedule(spec: BackgroundJobSpec)

    public suspend fun cancel(id: String)

    public suspend fun status(id: String): BackgroundJobStatus?

    public fun onStatusChange(id: String): Flow<BackgroundJobStatus>
}
