package com.lomo.data.util

import com.lomo.domain.model.StorageFilenameFormats
import java.time.LocalDate

object MemoLocalDateResolver {
    fun resolve(dateKey: String): LocalDate? = StorageFilenameFormats.parseOrNull(dateKey)
}
