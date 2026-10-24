package com.stickerstudio.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MiniJsonTest {

    @Test
    fun parsesNestedStructures() {
        val v = MiniJson.parse("""{"a":1,"b":[true,false,null,"x"],"c":{"d":-2.5e1}}""").asObject()!!
        assertEquals(1, v.optInt("a", 0))
        val b = v["b"].asArray()!!
        assertEquals(listOf(true, false, null, "x"), b)
        assertEquals(-25.0, (v["c"].asObject()!!["d"] as Double), 0.0001)
    }

    @Test
    fun parsesEscapesAndUnicode() {
        val v = MiniJson.parse("\"a\\n\\\"b\\\\ \\u0627\\u0644\"") as String
        assertEquals("a\n\"b\\ ال", v)
    }

    @Test
    fun roundTripKeepsArabicAndControlCharacters() {
        val original = linkedMapOf<String, Any?>("name" to "حزمة \"ملصقات\"\n2", "n" to 3, "f" to 1.5, "ok" to true, "nil" to null)
        val back = MiniJson.parse(MiniJson.stringify(original)).asObject()!!
        assertEquals("حزمة \"ملصقات\"\n2", back.optString("name"))
        assertEquals(3, back.optInt("n", 0))
        assertEquals(1.5f, back.optFloat("f", 0f), 0.0001f)
        assertEquals(true, back.optBool("ok", false))
        assertNull(back["nil"])
    }

    @Test
    fun largeIntegersStayExact() {
        val v = MiniJson.parse("""{"t":1792719888000}""").asObject()!!
        assertEquals(1792719888000L, v.optLong("t", 0))
    }

    @Test
    fun rejectsMalformedInput() {
        val bad = listOf("", "{", "[1,", "{\"a\"}", "{\"a\":}", "tru", "\"abc", "{\"a\":1} x", "[1 2]", "-", "{'a':1}")
        for (text in bad) {
            try {
                MiniJson.parse(text)
                fail("Should have failed: $text")
            } catch (e: JsonParseException) {
                // expected
            }
        }
    }

    @Test
    fun rejectsExcessiveNesting() {
        val deep = "[".repeat(200) + "]".repeat(200)
        try {
            MiniJson.parse(deep)
            fail("Should have failed")
        } catch (e: JsonParseException) {
            assertTrue(e.message!!.contains("deep"))
        }
    }
}
