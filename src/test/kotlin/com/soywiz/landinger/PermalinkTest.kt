package com.soywiz.landinger

import com.soywiz.landinger.util.canonicalPermalink
import kotlin.test.Test
import kotlin.test.assertEquals

class PermalinkTest {
    @Test
    fun test() {
        assertEquals("/", "".canonicalPermalink())
        assertEquals("/", "/".canonicalPermalink())
        assertEquals("/", "//".canonicalPermalink())
        assertEquals("/hello", "/hello".canonicalPermalink())
        assertEquals("/hello", "/hello/".canonicalPermalink())
    }
}