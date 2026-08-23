package ac.onyx.permetic.transport

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OriginAllowlistTest {
    private val allowed = setOf("https://appassets.androidplatform.net")

    @Test
    fun `exact origin match is allowed`() {
        assertTrue(isOriginAllowed("https://appassets.androidplatform.net", allowed))
    }

    @Test
    fun `a trailing slash on either side does not affect the match`() {
        assertTrue(isOriginAllowed("https://appassets.androidplatform.net/", allowed))
        assertTrue(
            isOriginAllowed(
                "https://appassets.androidplatform.net",
                setOf("https://appassets.androidplatform.net/"),
            ),
        )
    }

    @Test
    fun `a different scheme is rejected`() {
        assertFalse(isOriginAllowed("http://appassets.androidplatform.net", allowed))
    }

    @Test
    fun `a different host is rejected`() {
        assertFalse(isOriginAllowed("https://evil.example.com", allowed))
    }

    @Test
    fun `a different port is rejected`() {
        assertFalse(isOriginAllowed("https://appassets.androidplatform.net:8443", allowed))
    }

    @Test
    fun `a subdomain is rejected, not treated as a wildcard match`() {
        assertFalse(isOriginAllowed("https://sub.appassets.androidplatform.net", allowed))
    }

    @Test
    fun `a literal wildcard entry never matches a real origin`() {
        assertFalse(isOriginAllowed("https://anything.example.com", setOf("*")))
    }

    @Test
    fun `an empty allowlist rejects everything`() {
        assertFalse(isOriginAllowed("https://appassets.androidplatform.net", emptySet()))
    }

    @Test
    fun `file origin is never allowed even if somehow registered`() {
        assertFalse(isOriginAllowed("file:///android_asset/index.html", allowed))
    }

    @Test
    fun `matches against any of multiple registered origins`() {
        val multi = setOf("https://appassets.androidplatform.net", "https://other.example.com")
        assertTrue(isOriginAllowed("https://other.example.com", multi))
    }
}
