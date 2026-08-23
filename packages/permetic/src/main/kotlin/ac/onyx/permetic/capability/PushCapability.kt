package ac.onyx.permetic.capability

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors the `'granted' | 'denied' | 'prompt'` union in `index.d.ts`. `requestPermission()`
 * only ever returns [GRANTED] or [DENIED]; reusing this one enum for both methods is a
 * deliberate simplification over mirroring each method's narrower TS return union.
 */
@Serializable
public enum class PermissionState {
    @SerialName("granted")
    GRANTED,

    @SerialName("denied")
    DENIED,

    @SerialName("prompt")
    PROMPT,
}

/** Mirrors the anonymous `{ title?, body? }` shape on `PushMessage.notification`. */
@Serializable
public data class PushNotificationPreview(
    val title: String? = null,
    val body: String? = null,
)

/** Mirrors `PushMessage` in `index.d.ts`. [sentAt] is epoch millis. */
@Serializable
public data class PushMessage(
    val messageId: String,
    val data: Map<String, String>,
    val notification: PushNotificationPreview? = null,
    val sentAt: Long? = null,
)

/** Mirrors `PushCapability` in `index.d.ts`. Webview only. */
public interface PushCapability {
    /** Requests `POST_NOTIFICATIONS` on Android 13+. */
    public suspend fun requestPermission(): PermissionState

    public suspend fun permissionState(): PermissionState

    public suspend fun getToken(): String

    public fun onToken(): Flow<String>

    public fun onMessage(): Flow<PushMessage>

    /** The notification that launched the app, if any. Consumed once. */
    public suspend fun initialMessage(): PushMessage?
}
