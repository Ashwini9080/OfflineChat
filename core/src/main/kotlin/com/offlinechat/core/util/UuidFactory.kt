package com.offlinechat.core.util

import java.util.UUID

/**
 * Centralised UUID generation.
 *
 * Using a factory (rather than calling UUID.randomUUID() inline everywhere)
 * makes IDs easy to mock in tests and allows swapping the generation strategy
 * (e.g., time-ordered ULIDs) in a single place.
 */
object UuidFactory {

    /** Generate a new random UUID v4 string. */
    fun newId(): String = UUID.randomUUID().toString()
}
