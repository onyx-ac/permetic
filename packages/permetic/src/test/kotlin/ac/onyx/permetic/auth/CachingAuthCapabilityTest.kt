package ac.onyx.permetic.auth

import ac.onyx.permetic.capability.Account
import ac.onyx.permetic.capability.AuthToken
import ac.onyx.permetic.capability.CapabilityException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val TEN_MINUTES = 10 * 60 * 1000L

/**
 * A [TokenProvider] that does no caching of its own (per the interface contract), so
 * every [fetchToken] here is a call the capability decided it genuinely needed —
 * which is exactly what these tests count.
 *
 * [gate], when set, holds every fetch open until completed, so overlapping callers
 * can be observed mid-flight rather than inferred from timing.
 */
private class FakeTokenProvider : TokenProvider {
    var fetchCalls = 0
    var signOutCalls = 0
    val invalidated = mutableListOf<AuthToken>()
    var gate: CompletableDeferred<Unit>? = null
    var failWith: Exception? = null
    var account: Account? = Account("a@example.com", "a@example.com")
    var expiresAt: Long = TEN_MINUTES

    override suspend fun fetchToken(
        scopes: List<String>,
        interactive: Boolean,
    ): AuthToken {
        fetchCalls++
        val issued = fetchCalls
        gate?.await()
        failWith?.let { throw it }
        return AuthToken(accessToken = "tok-$issued", expiresAt = expiresAt, scopes = scopes)
    }

    override suspend fun invalidate(token: AuthToken) {
        invalidated += token
    }

    override suspend fun currentAccount(): Account? = account

    override suspend fun signOut() {
        signOutCalls++
        account = null
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CachingAuthCapabilityTest {
    private var now = 0L
    private val provider = FakeTokenProvider()

    private fun capability() = CachingAuthCapability(provider, clock = { now })

    @Test
    fun `a second call for the same scopes is served from cache`() =
        runTest {
            val auth = capability()

            assertEquals("tok-1", auth.getToken(emptyList()).accessToken)
            assertEquals("tok-1", auth.getToken(emptyList()).accessToken)

            assertEquals(1, provider.fetchCalls)
        }

    @Test
    fun `scope order does not fragment the cache`() =
        runTest {
            val auth = capability()

            auth.getToken(listOf("b", "a"))
            auth.getToken(listOf("a", "b"))

            assertEquals(1, provider.fetchCalls)
        }

    @Test
    fun `different scope sets are cached separately`() =
        runTest {
            val auth = capability()

            auth.getToken(listOf("a"))
            auth.getToken(listOf("b"))

            assertEquals(2, provider.fetchCalls)
        }

    @Test
    fun `an expired token is refetched`() =
        runTest {
            val auth = capability()
            auth.getToken(emptyList())

            now = TEN_MINUTES

            assertEquals("tok-2", auth.getToken(emptyList()).accessToken)
            assertEquals(2, provider.fetchCalls)
        }

    @Test
    fun `a token inside the expiry skew is treated as already expired`() =
        runTest {
            val auth = capability()
            auth.getToken(emptyList())

            // 30s before the real expiry, i.e. inside the default 60s skew.
            now = TEN_MINUTES - 30_000L

            assertEquals("tok-2", auth.getToken(emptyList()).accessToken)
            assertEquals(2, provider.fetchCalls)
        }

    @Test
    fun `concurrent callers share one fetch instead of each triggering their own`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            provider.gate = gate
            val auth = capability()

            val callers = List(5) { async { auth.getToken(emptyList()) } }
            runCurrent()

            assertEquals(1, provider.fetchCalls)

            gate.complete(Unit)
            val tokens = callers.awaitAll()

            assertTrue(tokens.all { it.accessToken == "tok-1" })
            assertEquals(1, provider.fetchCalls)
        }

    @Test
    fun `refresh never returns the cached token and invalidates the rejected one`() =
        runTest {
            val auth = capability()
            val first = auth.getToken(emptyList())

            val refreshed = auth.refresh(emptyList())

            assertNotEquals(first.accessToken, refreshed.accessToken)
            assertEquals(listOf(first), provider.invalidated)
            assertEquals(2, provider.fetchCalls)
        }

    @Test
    fun `concurrent refreshes collapse into one fetch`() =
        runTest {
            val auth = capability()
            val gate = CompletableDeferred<Unit>()
            provider.gate = gate

            val callers = List(3) { async { auth.refresh(emptyList()) } }
            runCurrent()

            assertEquals(1, provider.fetchCalls)

            gate.complete(Unit)
            callers.awaitAll()
            assertEquals(1, provider.fetchCalls)
        }

    /**
     * The subtle one. `refresh()` means "the token I hold was rejected", so joining a
     * `getToken()` fetch that was already in flight would hand back a token fetched
     * before that rejection — exactly what the caller told us not to do.
     */
    @Test
    fun `refresh does not ride along with a getToken fetch already in flight`() =
        runTest {
            val auth = capability()
            val gate = CompletableDeferred<Unit>()
            provider.gate = gate

            val get = async { auth.getToken(emptyList()) }
            runCurrent()
            assertEquals(1, provider.fetchCalls)

            val refresh = async { auth.refresh(emptyList()) }
            runCurrent()
            // Still 1: the refresh is waiting for the slot, not fetching yet.
            assertEquals(1, provider.fetchCalls)

            gate.complete(Unit)

            assertNotEquals(get.await().accessToken, refresh.await().accessToken)
            assertEquals(2, provider.fetchCalls)
        }

    @Test
    fun `a failed fetch frees the slot rather than deadlocking the next caller`() =
        runTest {
            val auth = capability()
            provider.failWith = CapabilityException.unauthenticated("no account")

            assertFailsWith<CapabilityException> { auth.getToken(emptyList()) }

            provider.failWith = null
            assertEquals("tok-2", auth.getToken(emptyList()).accessToken)
        }

    @Test
    fun `signOut clears the cache and reports the account change`() =
        runTest {
            val auth = capability()
            val seen = mutableListOf<String?>()
            backgroundScope.launch { auth.onAccountChange().toList(seen) }
            runCurrent()

            auth.getToken(emptyList())
            runCurrent()
            auth.signOut()
            runCurrent()

            assertEquals(listOf("a@example.com", null), seen)
            assertEquals(1, provider.signOutCalls)

            // Cache really is empty, not just marked stale.
            auth.getToken(emptyList())
            assertEquals(2, provider.fetchCalls)
        }

    @Test
    fun `a fetch already in flight during signOut does not repopulate the cache`() =
        runTest {
            val auth = capability()
            val gate = CompletableDeferred<Unit>()
            provider.gate = gate

            val inFlight = async { auth.getToken(emptyList()) }
            runCurrent()

            auth.signOut()
            gate.complete(Unit)
            inFlight.await()
            provider.gate = null

            // Would be 1 if the in-flight fetch had written its result into the cache
            // after sign-out cleared it.
            auth.getToken(emptyList())
            assertEquals(2, provider.fetchCalls)
        }

    @Test
    fun `an account switch is reported once, not on every fetch`() =
        runTest {
            val auth = capability()
            val seen = mutableListOf<String?>()
            backgroundScope.launch { auth.onAccountChange().toList(seen) }
            runCurrent()

            auth.getToken(listOf("a"))
            runCurrent()
            auth.getToken(listOf("b"))
            runCurrent()

            provider.account = Account("b@example.com", "b@example.com")
            auth.getToken(listOf("c"))
            runCurrent()

            assertEquals(listOf<String?>("a@example.com", "b@example.com"), seen)
        }
}
