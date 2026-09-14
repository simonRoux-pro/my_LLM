package pro.simonroux.myllm.core.model

/**
 * Explicit success/failure carrier.
 *
 * Kotlin's own Result is not used because it cannot be a return type of a
 * suspend function in all positions and because the failure side here needs to
 * distinguish recoverable from fatal.
 */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val error: AppError) : Outcome<Nothing>

    val isOk: Boolean get() = this is Ok

    fun getOrNull(): T? = (this as? Ok)?.value
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Ok -> Outcome.Ok(transform(value))
    is Outcome.Err -> this
}

inline fun <T> Outcome<T>.onErr(block: (AppError) -> Unit): Outcome<T> {
    if (this is Outcome.Err) block(error)
    return this
}

/** Failures the UI has to tell apart. */
sealed class AppError(open val message: String, open val cause: Throwable? = null) {
    data class Network(override val message: String, override val cause: Throwable? = null) :
        AppError(message, cause)

    data class Offline(override val message: String = "Mode hors ligne actif") : AppError(message)

    data class ModelNotLoaded(override val message: String = "Aucun modèle chargé") : AppError(message)

    data class OutOfMemory(override val message: String) : AppError(message)

    data class Auth(override val message: String) : AppError(message)

    data class RateLimited(override val message: String, val retryAfterSeconds: Long?) : AppError(message)

    data class SkillFailure(val skillName: String, override val message: String) : AppError(message)

    data class Cancelled(override val message: String = "Annulé") : AppError(message)

    data class Unexpected(override val message: String, override val cause: Throwable? = null) :
        AppError(message, cause)
}
