package ac.onyx.permetic.system

import ac.onyx.permetic.capability.AppLifecycleState
import ac.onyx.permetic.capability.CapabilityException
import ac.onyx.permetic.capability.HostKind
import ac.onyx.permetic.capability.LogLevel
import ac.onyx.permetic.capability.SharePayload
import ac.onyx.permetic.capability.SystemCapability
import ac.onyx.permetic.capability.SystemInfo
import ac.onyx.permetic.transport.BridgeErrorCode
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * The real `system` capability (spec 01, task 6).
 *
 * The `Activity` is held weakly, like `PermeticController`'s. Unlike the controller's,
 * losing it is not fatal here: [share] and [openUrl] fall back to the application
 * context with `FLAG_ACTIVITY_NEW_TASK`, which genuinely works. Returning
 * `UNAVAILABLE` (spec 01's lifecycle rule) when the call would in fact succeed would
 * be a worse contract, so the rule is deliberately not applied to these two — see
 * [launch]. Hosts that keep one controller across a configuration change should call
 * [rebind] with the new `Activity` to get the in-task chooser back.
 *
 * [debugLogging] gates `LogLevel.DEBUG` only. Pass `BuildConfig.DEBUG` from the
 * embedding app: web-app log lines land in logcat, and debug-level chatter should not
 * ship in release builds.
 */
public class AndroidSystemCapability(
    activity: Activity,
    private val logTag: String = "Permetic",
    private val debugLogging: Boolean = false,
) : SystemCapability {
    private val appContext: Context = activity.applicationContext

    @Volatile
    private var activityRef = WeakReference(activity)

    /** Re-points this capability at the post-configuration-change `Activity`. */
    public fun rebind(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    override suspend fun info(): SystemInfo {
        val pkg =
            try {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                throw CapabilityException(
                    BridgeErrorCode.INTERNAL,
                    "own package info missing",
                    e,
                )
            }
        return SystemInfo(
            host = HostKind.WEBVIEW,
            appVersion = pkg.versionName.orEmpty(),
            appVersionCode = PackageInfoCompat.getLongVersionCode(pkg).toInt(),
            osVersion = Build.VERSION.SDK_INT,
            locale = currentLocale(),
            lifecycle = currentLifecycle(),
        )
    }

    override fun log(
        level: LogLevel,
        message: String,
    ) {
        when (level) {
            LogLevel.DEBUG -> if (debugLogging) Log.d(logTag, message)
            LogLevel.INFO -> Log.i(logTag, message)
            LogLevel.WARN -> Log.w(logTag, message)
            LogLevel.ERROR -> Log.e(logTag, message)
        }
    }

    override suspend fun share(payload: SharePayload) {
        val body = listOfNotNull(payload.text, payload.url).joinToString(separator = "\n")
        if (body.isEmpty()) {
            throw CapabilityException.invalidArgument("share needs at least a text or a url")
        }
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                payload.title?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                putExtra(Intent.EXTRA_TEXT, body)
            }
        launch(Intent.createChooser(send, payload.title), what = "sharing")
    }

    override suspend fun openUrl(url: String) {
        if (!isExternalUrlAllowed(url)) {
            throw CapabilityException.invalidArgument("openUrl accepts http(s) URLs only")
        }
        val view =
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                // Steers the implicit intent at browsers rather than at whatever else
                // registered for the host's domain — including this app itself.
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
        launch(view, what = "opening a link")
    }

    override fun onLifecycle(): Flow<AppLifecycleState> =
        callbackFlow {
            val observer =
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        trySend(AppLifecycleState.FOREGROUND)
                    }

                    override fun onStop(owner: LifecycleOwner) {
                        trySend(AppLifecycleState.BACKGROUND)
                    }
                }
            // Emits the current state immediately on registration, so a subscriber
            // never has to wait for the next transition to learn where it stands.
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            lifecycle.addObserver(observer)
            awaitClose { lifecycle.removeObserver(observer) }
        }.flowOn(Dispatchers.Main.immediate)

    private fun currentLocale(): String =
        ConfigurationCompat.getLocales(appContext.resources.configuration)[0]
            ?.toLanguageTag()
            ?: Locale.getDefault().toLanguageTag()

    private suspend fun currentLifecycle(): AppLifecycleState =
        withContext(Dispatchers.Main.immediate) {
            val state = ProcessLifecycleOwner.get().lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.STARTED)) {
                AppLifecycleState.FOREGROUND
            } else {
                AppLifecycleState.BACKGROUND
            }
        }

    /**
     * Starting an Activity must happen on the main thread, and bridge work does not
     * run there (root `CLAUDE.md`) — hence the explicit hop. Falls back to the
     * application context when the bound `Activity` is gone; see the class KDoc.
     */
    private suspend fun launch(
        intent: Intent,
        what: String,
    ) = withContext(Dispatchers.Main.immediate) {
        val activity = activityRef.get()
        try {
            if (activity != null) {
                activity.startActivity(intent)
            } else {
                appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: ActivityNotFoundException) {
            throw CapabilityException(
                BridgeErrorCode.UNAVAILABLE,
                "no installed app handles $what",
                e,
            )
        }
    }
}
