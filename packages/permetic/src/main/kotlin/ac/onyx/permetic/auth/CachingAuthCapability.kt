package ac.onyx.permetic.auth

import ac.onyx.permetic.capability.Account
import ac.onyx.permetic.capability.AuthCapability
import ac.onyx.permetic.capability.AuthToken
import ac.onyx.permetic.capability.CapabilityException
import ac.onyx.permetic.transport.BridgeErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The real `auth` capability (spec 01, task 6): token caching plus the `refresh()`
 * path a caller takes after a downstream API answers 401. Identity itself is
 * [TokenProvider]'s job.
 *
 * **Single-flight.** A web app cold-starting typically fires several requests at
 * once, each needing a token. Without collapsing them that is N provider round trips
 * and, worse, potentially N account pickers. Concurrent calls for the same scope set
 * share one in-flight fetch.
 *
 * **`refresh()` is not `getToken()` with the cache cleared.** The caller is telling
 * us the token it holds was rejected, so a refresh must never return a token fetched
 * before that point — not from the cache, and not by joining a `getToken()` fetch
 * that was already running. It does collapse with other concurrent refreshes, which
 * is the common case (several parallel requests all 401 at once).
 *
 * Tokens are keyed by the *sorted* scope list, so argument order from JS doesn't
 * fragment the cache. A token with [AuthToken.expiresAt] of `0` is always treated as
 * expired — a provider that cannot report a real expiry effectively disables caching
 * for its tokens, which is the safe direction to fail.
 */
public class CachingAuthCapability(
    private val provider: TokenProvider,
    private val expirySkewMillis: Long = DEFAULT_EXPIRY_SKEW_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) : AuthCapability {
    private class InFlight(
        val deferred: CompletableDeferred<AuthToken>,
        val forced: Boolean,
    )

    private val mutex = Mutex()
    private val cache = mutableMapOf<List<String>, AuthToken>()
    private val inFlight = mutableMapOf<List<String>, InFlight>()

    /**
     * Bumped by [signOut]. A fetch that was already running when sign-out happened
     * must not repopulate the cache with the signed-out account's token, so an owner
     * only writes its result if the epoch it captured is still current.
     */
    private var epoch: Int = 0
    private var lastAccountId: String? = null

    private val accountChanges =
        MutableSharedFlow<String?>(
            extraBufferCapacity = ACCOUNT_EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override suspend fun getToken(
        scopes: List<String>,
        interactive: Boolean,
    ): AuthToken {
        val key = scopes.sorted()
        mutex.withLock { fresh(key) }?.let { return it }
        return fetch(key, interactive = interactive, forced = false, invalidating = null)
    }

    override suspend fun refresh(scopes: List<String>): AuthToken {
        val key = scopes.sorted()
        val rejected = mutex.withLock { cache.remove(key) }
        return fetch(key, interactive = false, forced = true, invalidating = rejected)
    }

    override suspend fun signOut() {
        val hadAccount =
            mutex.withLock {
                cache.clear()
                epoch++
                val had = lastAccountId != null
                lastAccountId = null
                had
            }
        provider.signOut()
        if (hadAccount) accountChanges.emit(null)
    }

    override suspend fun currentAccount(): Account? = provider.currentAccount()

    /**
     * Fires on sign-in, sign-out and account switch — changes only. Read
     * [currentAccount] for the state as it stands now; this flow deliberately does
     * not replay it, so "the account changed" and "here is the account" stay
     * distinguishable.
     */
    override fun onAccountChange(): Flow<String?> = accountChanges.asSharedFlow()

    // The three returns are the three ways this legitimately settles — cache hit,
    // we ran the fetch, we joined someone else's — and each exits a different point
    // of the retry loop. Folding them into one exit would need a result variable
    // threaded through the loop, which reads worse than it reads now.
    @Suppress("ReturnCount")
    private suspend fun fetch(
        key: List<String>,
        interactive: Boolean,
        forced: Boolean,
        invalidating: AuthToken?,
    ): AuthToken {
        var toInvalidate = invalidating
        repeat(MAX_ATTEMPTS) { attempt ->
            val own = CompletableDeferred<AuthToken>()
            val existing =
                mutex.withLock {
                    // A refresh must ignore the cache entirely: see the class KDoc.
                    if (!forced) fresh(key)?.let { return it }
                    inFlight[key] ?: run {
                        inFlight[key] = InFlight(own, forced)
                        null
                    }
                }

            if (existing == null) {
                val invalidate = toInvalidate
                toInvalidate = null
                return runOwnedFetch(key, interactive, invalidate, own)
            }

            val outcome = awaitJoined(existing.deferred)
            // A refresh accepts a result only from another refresh. Otherwise it was
            // merely waiting for the slot to clear so it can run its own fetch.
            if (!forced || existing.forced) {
                outcome.getOrNull()?.let { return it }
                if (attempt == MAX_ATTEMPTS - 1) {
                    throw outcome.exceptionOrNull()
                        ?: CapabilityException(BridgeErrorCode.INTERNAL, "token fetch failed")
                }
            }
        }
        throw CapabilityException(
            BridgeErrorCode.INTERNAL,
            "token fetch did not settle after $MAX_ATTEMPTS attempts",
        )
    }

    /**
     * The slot in [inFlight] is ours: do the real work, then free it and wake every
     * joiner — on the failure path too, which is why the catch is broad and
     * unconditionally re-throws. Leaving the slot occupied after a failure would
     * deadlock every subsequent caller for this scope set.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runOwnedFetch(
        key: List<String>,
        interactive: Boolean,
        invalidating: AuthToken?,
        own: CompletableDeferred<AuthToken>,
    ): AuthToken {
        val captured = mutex.withLock { epoch }
        try {
            invalidating?.let { provider.invalidate(it) }
            val token = provider.fetchToken(key, interactive)
            mutex.withLock {
                if (epoch == captured) cache[key] = token
                inFlight.remove(key)
            }
            own.complete(token)
            noteAccount()
            return token
        } catch (e: Throwable) {
            mutex.withLock { inFlight.remove(key) }
            own.completeExceptionally(e)
            throw e
        }
    }

    /**
     * Awaits a fetch someone else owns, without letting their failure become our
     * crash — the caller decides whether to retry or propagate.
     *
     * A [CancellationException] is checked against our own coroutine first: if we are
     * still active it came from the *other* fetch being cancelled, which is their
     * failure to report, not our cancellation to honour.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun awaitJoined(deferred: CompletableDeferred<AuthToken>): Result<AuthToken> =
        try {
            Result.success(deferred.await())
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** Must be called with [mutex] held. */
    private fun fresh(key: List<String>): AuthToken? =
        cache[key]?.takeIf { it.expiresAt - expirySkewMillis > clock() }

    private suspend fun noteAccount() {
        val id = provider.currentAccount()?.id
        val changed =
            mutex.withLock {
                if (id != lastAccountId) {
                    lastAccountId = id
                    true
                } else {
                    false
                }
            }
        if (changed) accountChanges.emit(id)
    }

    private companion object {
        /** Treat a token as expired this long before it really is, to cover the
         * round trip between handing it to JS and the downstream API validating it. */
        const val DEFAULT_EXPIRY_SKEW_MILLIS = 60_000L

        /** One retry: enough to take over after the fetch we joined failed, without
         * letting a persistently failing provider be called in a loop. */
        const val MAX_ATTEMPTS = 2

        const val ACCOUNT_EVENT_BUFFER = 8
    }
}
