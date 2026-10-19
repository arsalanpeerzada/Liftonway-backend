package dev.liftonway.backend

import dev.liftonway.backend.bookings.CancellationPolicy
import dev.liftonway.backend.common.Invalid
import dev.liftonway.backend.common.LiftOnWayProperties
import dev.liftonway.backend.common.PhoneNumbers
import dev.liftonway.backend.common.WindowLimiter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulesTest {
    private val policy = CancellationPolicy(LiftOnWayProperties())
    private val departure = Instant.parse("2026-10-20T03:00:00Z") // 08:00 in Pakistan.

    @Test
    fun `cancelling is allowed up to exactly four hours before departure`() {
        assertTrue(policy.canCancel(departure, departure.minusSeconds(5 * 3600)))
        assertTrue(policy.canCancel(departure, departure.minusSeconds(4 * 3600)))
        assertFalse(policy.canCancel(departure, departure.minusSeconds(4 * 3600 - 60)))
        assertFalse(policy.canCancel(departure, departure.plusSeconds(60)))
    }

    @Test
    fun `phone numbers are normalised to E164 whichever way they're typed`() {
        assertEquals("+923001234567", PhoneNumbers.normalise("+92", "3001234567"))
        assertEquals("+923001234567", PhoneNumbers.normalise("+92", "0300 1234567"))
        assertEquals("+923001234567", PhoneNumbers.normalise(" +92 ", "0300-123-4567"))
    }

    @Test
    fun `incomplete or malformed numbers are rejected`() {
        assertThrows<Invalid> { PhoneNumbers.normalise("92", "3001234567") }
        assertThrows<Invalid> { PhoneNumbers.normalise("+92", "12345") }
    }

    @Test
    fun `masked numbers keep only the country prefix and last two digits`() {
        assertEquals("+92300*****67", PhoneNumbers.masked("+923001234567"))
    }

    @Test
    fun `the limiter allows a burst up to the limit, then says how long to wait until the window resets`() {
        val clock = MutableClock(Instant.parse("2026-10-19T05:00:10Z"))
        val limiter = WindowLimiter(clock, limit = 3, window = Duration.ofMinutes(1))
        repeat(3) { assertNull(limiter.acquire("a")) }
        assertEquals(Duration.ofSeconds(50), limiter.acquire("a"))
        assertNull(limiter.acquire("b"), "each key counts separately")
        clock.now = Instant.parse("2026-10-19T05:01:00Z")
        assertNull(limiter.acquire("a"), "a new window starts afresh")
    }
}
