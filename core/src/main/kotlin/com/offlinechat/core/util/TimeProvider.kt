package com.offlinechat.core.util

/**
 * Abstraction over the system clock.
 *
 * Injecting [TimeProvider] instead of calling [System.currentTimeMillis]
 * directly allows tests to control time deterministically.
 */
interface TimeProvider {
    /** Current wall-clock time in milliseconds since Unix epoch. */
    fun nowMillis(): Long
}

/** Production implementation backed by [System.currentTimeMillis]. */
object SystemTimeProvider : TimeProvider {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
