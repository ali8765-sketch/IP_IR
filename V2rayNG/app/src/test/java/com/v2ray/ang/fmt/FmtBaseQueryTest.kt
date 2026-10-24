package com.v2ray.ang.fmt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI

/** Chapar: the query parser behind every imported link (audit finding B11). */
class FmtBaseQueryTest {

    private val fmt = FmtBase()

    @Test
    fun aPartWithoutAValueIsSkippedInsteadOfThrowing() {
        assertEquals(mapOf("a" to "1"), fmt.getQueryParam(URI("vless://u@h:1?a=1&#x")))
        assertEquals(mapOf("a" to "1", "c" to "2"), fmt.getQueryParam(URI("vless://u@h:1?a=1&b&c=2#x")))
    }

    @Test
    fun aValueMayContainAnEqualsSign() {
        assertEquals(mapOf("pbk" to "abc=", "sid" to "1"), fmt.getQueryParam(URI("vless://u@h:1?pbk=abc=&sid=1#x")))
    }

    @Test
    fun valuesAreDecoded() {
        assertEquals(mapOf("path" to "/a b"), fmt.getQueryParam(URI("vless://u@h:1?path=%2Fa%20b#x")))
    }
}
