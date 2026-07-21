package com.boltffi.demo

import kotlin.test.Test

class DemoSmokeTest {
    @Test
    fun smokeMainExercisesGeneratedBindings() {
        assertJvmMainSucceeds("com.boltffi.demo.SmokeKt")
    }
}
