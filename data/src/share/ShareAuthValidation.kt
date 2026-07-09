package com.lomo.data.share

import io.ktor.http.HttpStatusCode

internal data class ShareAuthValidation(
    val ok: Boolean,
    val status: HttpStatusCode = HttpStatusCode.OK,
    val message: String = "",
    val keyHex: String? = null,
)
