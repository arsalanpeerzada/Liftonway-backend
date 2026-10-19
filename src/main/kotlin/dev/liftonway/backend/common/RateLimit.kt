package dev.liftonway.backend.common

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fixed-window counters kept in memory. Each server counts on its own, so with N servers the real limit is up
 * to N times higher: fine as an abuse brake. Move the counters to Redis or PostgreSQL if exact limits matter.
 */
class WindowLimiter(private val clock: Clock, private val limit: Int, private val window: Duration) {
    private class Bucket(val start: Long, val count: AtomicInteger = AtomicInteger())

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** Null when [key] may go ahead; otherwise how long until its window resets. */
    fun acquire(key: String): Duration? {
        val now = clock.millis()
        val start = now - now % window.toMillis()
        val bucket = buckets.compute(key) { _, old -> if (old == null || old.start != start) Bucket(start) else old }!!
        if (bucket.count.incrementAndGet() <= limit) return null
        return Duration.ofMillis(start + window.toMillis() - now)
    }

    /** Drops finished windows so the map doesn't grow without end. */
    fun prune() {
        val now = clock.millis()
        buckets.entries.removeIf { now - it.value.start >= window.toMillis() }
    }
}

/**
 * Limits every API call: per signed-in user, or per client address when signed out. Asking for sign-in codes
 * has its own tighter per-address limit on top of the per-number one, so nobody can make the server text
 * thousands of numbers (SMS pumping).
 */
class RateLimitFilter(clock: Clock, settings: LiftOnWayProperties.RateLimit) : OncePerRequestFilter() {
    private val perUser = WindowLimiter(clock, settings.perUserPerMinute, Duration.ofMinutes(1))
    private val perAddress = WindowLimiter(clock, settings.perAddressPerMinute, Duration.ofMinutes(1))
    private val codes = WindowLimiter(clock, settings.codesPerAddressPerHour, Duration.ofHours(1))
    private val all = listOf(perUser, perAddress, codes)
    private val calls = AtomicInteger()

    override fun shouldNotFilter(request: HttpServletRequest) = !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (calls.incrementAndGet() % 10_000 == 0) all.forEach(WindowLimiter::prune)
        val user = (SecurityContextHolder.getContext().authentication?.principal as? Jwt)?.subject
        val address = request.remoteAddr
        val wait = (if (user != null) perUser.acquire(user) else perAddress.acquire(address))
            ?: if (request.method == "POST" && request.requestURI.endsWith("/otp")) codes.acquire(address) else null
        if (wait == null) {
            chain.doFilter(request, response)
            return
        }
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.setHeader(HttpHeaders.RETRY_AFTER, wait.toSeconds().coerceAtLeast(1).toString())
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.writer.write(
            """{"type":"about:blank","title":"Too Many Requests","status":429,"code":"TOO_MANY_REQUESTS","detail":"You're going a bit fast. Try again in a moment."}""",
        )
    }
}
