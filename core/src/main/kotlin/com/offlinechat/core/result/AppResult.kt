package com.offlinechat.core.result

/**
 * A discriminated union representing the outcome of an operation that can fail.
 *
 * This replaces throwing exceptions for expected failure states (e.g., Bluetooth
 * not available, decryption failure) while keeping error information typed.
 *
 * Usage:
 * ```kotlin
 * val result = someOperation()
 * when (result) {
 *     is AppResult.Success -> handleSuccess(result.data)
 *     is AppResult.Failure -> handleError(result.error)
 * }
 * ```
 */
sealed class AppResult<out T> {

    /** The operation completed successfully. [data] holds the return value. */
    data class Success<out T>(val data: T) : AppResult<T>()

    /**
     * The operation failed. [error] is a typed description of what went wrong.
     *
     * We carry a [cause] for logging/debugging but never propagate raw
     * exceptions to the UI layer — use [error] for user-facing messages.
     */
    data class Failure(
        val error: AppError,
        val cause: Throwable? = null,
    ) : AppResult<Nothing>()

    // ── Convenience accessors ──────────────────────────────────────────────────

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    /** Returns [data] if Success, null otherwise. */
    fun getOrNull(): T? = (this as? Success)?.data

    /** Returns [data] if Success, or throws [AppException] if Failure. */
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Failure -> throw AppException(error, cause)
    }

    // ── Transformations ───────────────────────────────────────────────────────

    /** Transform a successful result. Failures pass through unchanged. */
    inline fun <R> map(transform: (T) -> R): AppResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }

    /** Execute [block] only on success, returning the same result. */
    inline fun onSuccess(block: (T) -> Unit): AppResult<T> {
        if (this is Success) block(data)
        return this
    }

    /** Execute [block] only on failure, returning the same result. */
    inline fun onFailure(block: (AppError, Throwable?) -> Unit): AppResult<T> {
        if (this is Failure) block(error, cause)
        return this
    }
}

// ── Typed error domain ────────────────────────────────────────────────────────

/**
 * All expected failure reasons across the app.
 *
 * Keeping errors as a sealed hierarchy (instead of raw strings) means the
 * compiler will warn when a new error type is added and not handled everywhere.
 */
sealed class AppError {

    // Transport errors
    data object BluetoothNotAvailable      : AppError()
    data object BluetoothPermissionDenied  : AppError()
    data object PeerNotReachable           : AppError()
    data class  TransportSendFailed(val detail: String) : AppError()

    // Security errors
    data object IdentityNotInitialized     : AppError()
    data object SignatureVerificationFailed: AppError()
    data object DecryptionFailed           : AppError()
    data object KeyGenerationFailed        : AppError()
    data object UnknownPeer               : AppError()

    // Storage errors
    data object DatabaseWriteFailed        : AppError()
    data object MessageNotFound            : AppError()

    // Messaging errors
    data object MessageTooLarge            : AppError()
    data class  InvalidEnvelope(val detail: String) : AppError()

    // General
    data class  Unknown(val detail: String) : AppError()
}

/** Wraps an [AppError] as a throwable, for use with APIs that require exceptions. */
class AppException(
    val appError: AppError,
    cause: Throwable? = null,
) : Exception(appError.toString(), cause)
