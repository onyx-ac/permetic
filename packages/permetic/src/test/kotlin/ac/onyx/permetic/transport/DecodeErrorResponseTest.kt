package ac.onyx.permetic.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecodeErrorResponseTest {
    @Test
    fun `recovers the id from otherwise-invalid json for correlation`() {
        val text = """{"v":1,"id":"req-1","capability":"bogus","method":"x","args":[]}"""
        val response =
            decodeErrorResponse(text, IllegalStateException("unknown capability 'bogus'"))

        assertTrue(response is BridgeResponse.Failure)
        assertEquals("req-1", response.id)
        assertEquals(BridgeErrorCode.INVALID_ARGUMENT, response.error.code)
        assertEquals(CONTRACT_VERSION, response.v)
    }

    @Test
    fun `falls back to a placeholder id when the text has no recoverable id`() {
        val response = decodeErrorResponse("{not valid json at all", IllegalStateException("boom"))

        assertTrue(response is BridgeResponse.Failure)
        assertEquals("unknown", response.id)
        assertEquals(BridgeErrorCode.INVALID_ARGUMENT, response.error.code)
    }

    @Test
    fun `falls back to a placeholder id when text is valid json but not an object`() {
        val response = decodeErrorResponse("42", IllegalStateException("boom"))

        assertTrue(response is BridgeResponse.Failure)
        assertEquals("unknown", response.id)
    }

    @Test
    fun `carries the original error message through`() {
        val response =
            decodeErrorResponse("""{"id":"req-2"}""", IllegalStateException("specific reason"))

        assertTrue(response is BridgeResponse.Failure)
        assertEquals("specific reason", response.error.message)
    }
}
