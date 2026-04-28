package com.lomo.domain.repository

/**
 * Logging facade for the domain layer. Implementations are provided by the
 * app or data layer (e.g. Timber-backed). Domain code must never depend on
 * Android logging frameworks directly.
 */
interface DomainLogger {
    fun warn(message: String, throwable: Throwable? = null)
    fun error(message: String, throwable: Throwable? = null)
}
