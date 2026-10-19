package dev.liftonway.backend.common

import org.slf4j.LoggerFactory
import org.springframework.beans.TypeMismatchException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.time.Duration

/**
 * A failure the client can act on. Every one becomes an RFC 9457 problem response with a stable `code`
 * the app can switch on, e.g. `{"status":409,"code":"CANCEL_WINDOW_CLOSED","detail":"..."}`.
 */
open class ApiException(val status: HttpStatus, val code: String, override val message: String, val retryAfter: Duration? = null) :
    RuntimeException(message)

class NotFound(what: String) : ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "$what not found.")
class Conflict(code: String, message: String) : ApiException(HttpStatus.CONFLICT, code, message)
class Invalid(code: String, message: String) : ApiException(HttpStatus.BAD_REQUEST, code, message)
class Forbidden(message: String) : ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message)
class TooManyRequests(message: String, retryAfter: Duration) : ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", message, retryAfter)

@RestControllerAdvice
class ErrorHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException): ResponseEntity<ProblemDetail> {
        val body = problem(e.status, e.code, e.message)
        val response = ResponseEntity.status(e.status)
        e.retryAfter?.let { response.header(HttpHeaders.RETRY_AFTER, it.toSeconds().coerceAtLeast(1).toString()) }
        return response.body(body)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException): ResponseEntity<ProblemDetail> {
        val fields = e.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "is invalid") }
        val body = problem(HttpStatus.BAD_REQUEST, "VALIDATION", "Some fields are invalid.").apply { setProperty("fields", fields) }
        return ResponseEntity.badRequest().body(body)
    }

    /** Spring's own client errors: unknown path, wrong method, missing parameter and the like. Not our bug, so not logged as one. */
    private fun framework(e: ErrorResponse): ResponseEntity<ProblemDetail> {
        val status = HttpStatus.resolve(e.statusCode.value()) ?: HttpStatus.BAD_REQUEST
        val (code, detail) = when (status) {
            HttpStatus.NOT_FOUND -> "NOT_FOUND" to "Not found."
            HttpStatus.METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED" to "That method isn't supported here."
            else -> "BAD_REQUEST" to "That request isn't valid."
        }
        return ResponseEntity.status(status).body(problem(status, code, detail))
    }

    /** A malformed body or a parameter of the wrong type, e.g. a date that isn't one. */
    @ExceptionHandler(HttpMessageNotReadableException::class, TypeMismatchException::class)
    fun unreadable(e: Exception): ResponseEntity<ProblemDetail> =
        ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "That request isn't valid."))

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ProblemDetail> {
        if (e is ErrorResponse && e.statusCode.is4xxClientError) return framework(e)
        // Logged without request bodies: they can hold phone numbers and codes.
        log.error("Unhandled {}", e.javaClass.simpleName, e)
        return ResponseEntity.internalServerError().body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Something went wrong."))
    }

    private fun problem(status: HttpStatus, code: String, detail: String) = ProblemDetail.forStatusAndDetail(status, detail).apply {
        type = URI.create("https://liftonway.dev/problems/${code.lowercase().replace('_', '-')}")
        setProperty("code", code)
    }
}
