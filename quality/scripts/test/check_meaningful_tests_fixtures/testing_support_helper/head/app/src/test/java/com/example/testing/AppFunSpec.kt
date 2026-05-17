package com.example.testing

import io.kotest.core.spec.style.FunSpec

abstract class AppFunSpec(body: FunSpec.() -> Unit = {}) : FunSpec(body)
