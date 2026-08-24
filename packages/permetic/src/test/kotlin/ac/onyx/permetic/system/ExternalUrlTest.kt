package ac.onyx.permetic.system

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `openUrl` forwards a web-app-supplied string to an implicit intent, so this
 * allowlist is a security boundary rather than input tidiness — see [isExternalUrlAllowed].
 */
class ExternalUrlTest {
    @Test
    fun `allows http and https with a real host`() {
        assertTrue(isExternalUrlAllowed("https://example.com"))
        assertTrue(isExternalUrlAllowed("http://example.com/path?q=1#frag"))
    }

    @Test
    fun `scheme comparison is case insensitive`() {
        assertTrue(isExternalUrlAllowed("HTTPS://example.com"))
    }

    @Test
    fun `rejects intent urls that could reach another app's components`() {
        assertFalse(isExternalUrlAllowed("intent://scan/#Intent;scheme=zxing;end"))
    }

    @Test
    fun `rejects schemes that read local state or execute`() {
        assertFalse(isExternalUrlAllowed("file:///etc/hosts"))
        assertFalse(isExternalUrlAllowed("content://com.example.provider/secrets"))
        assertFalse(isExternalUrlAllowed("javascript:alert(1)"))
        assertFalse(isExternalUrlAllowed("data:text/html,<script>alert(1)</script>"))
    }

    @Test
    fun `rejects input with no scheme at all`() {
        assertFalse(isExternalUrlAllowed("example.com"))
        assertFalse(isExternalUrlAllowed("//example.com"))
        assertFalse(isExternalUrlAllowed(""))
    }

    @Test
    fun `rejects a well-formed scheme pointing at no host`() {
        assertFalse(isExternalUrlAllowed("http://"))
    }

    @Test
    fun `rejects unparseable input rather than throwing`() {
        assertFalse(isExternalUrlAllowed("http://exa mple.com"))
        assertFalse(isExternalUrlAllowed("::::"))
    }
}
