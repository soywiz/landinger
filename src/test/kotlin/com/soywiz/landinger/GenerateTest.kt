package com.soywiz.landinger

import com.soywiz.landinger.modules.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class GenerateTest {
    @Test
    fun test() = runTest {
        generate(Config())
    }
}