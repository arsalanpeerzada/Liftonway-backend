package dev.liftonway.backend

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestClient
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A clock the tests can move, so the 4-hour rule can be checked either side of the line. */
class MutableClock(var now: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    override fun instant(): Instant = now
}

@TestConfiguration(proxyBeanMethods = false)
class TestInfra {
    @Bean
    @ServiceConnection
    fun postgres() = PostgreSQLContainer("postgres:17-alpine")

    @Bean
    @Primary
    fun testClock() = MutableClock(START)

    companion object {
        /** Sunday 19 October 2026, 10:00 in Pakistan. */
        val START: Instant = Instant.parse("2026-10-19T05:00:00Z")
    }
}

/** The core journey end to end against a real PostgreSQL, through the HTTP API the app will call. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestInfra::class)
class ApiFlowTest {
    @LocalServerPort var port = 0

    @Autowired lateinit var clock: MutableClock

    private val http by lazy { RestClient.builder().baseUrl("http://localhost:$port/api/v1").build() }

    data class Reply(val status: Int, val body: Any?, val headers: HttpHeaders) {
        @Suppress("UNCHECKED_CAST")
        val json get() = body as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val list get() = body as List<Map<String, Any?>>
        val code get() = json["code"]
    }

    private fun call(method: HttpMethod, path: String, body: Any? = null, token: String? = null): Reply =
        http.method(method).uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .headers { h -> token?.let { h.setBearerAuth(it) } }
            .apply { if (body != null) body(body) }
            .exchange { _, response ->
                val text = response.body.readAllBytes()
                val parsed = if (text.isEmpty()) null else tools.jackson.databind.json.JsonMapper.builder().build().readValue(text, Any::class.java)
                Reply(response.statusCode.value(), parsed, response.headers)
            }

    private fun post(path: String, body: Any? = null, token: String? = null) = call(HttpMethod.POST, path, body ?: emptyMap<String, Any>(), token)
    private fun get(path: String, token: String? = null) = call(HttpMethod.GET, path, token = token)
    private fun put(path: String, body: Any, token: String) = call(HttpMethod.PUT, path, body, token)

    private fun signUp(name: String, number: String): String {
        assertEquals(202, post("/auth/otp", mapOf("countryCode" to "+92", "number" to number)).status)
        val session = post("/auth/verify", mapOf("countryCode" to "+92", "number" to number, "code" to "1234", "name" to name))
        assertEquals(200, session.status, session.body.toString())
        return session.json["token"] as String
    }

    @BeforeEach
    fun resetClock() {
        clock.now = TestInfra.START
    }

    @Test
    fun `publish, find, request, accept and cancel a ride, with the 4-hour rule`() {
        val driver = signUp("Test Driver", "3001110001")
        val ride = mapOf(
            "from" to "f7", "to" to "saddar", "stops" to listOf("faizabad"),
            "departure" to "2026-10-20T08:00:00+05:00", "seats" to 2, "pricePerSeat" to 450,
        )

        assertEquals("VEHICLE_REQUIRED", post("/rides", ride, driver).code)
        val car = mapOf("name" to "Honda Civic", "model" to "2021", "plate" to "lea 4521", "colour" to "White", "airConditioning" to true, "heater" to false)
        assertEquals("LEA 4521", put("/me/vehicle", car, driver).json["plate"])

        val published = post("/rides", ride, driver)
        assertEquals(201, published.status, published.body.toString())
        val rideId = published.json["id"] as String
        assertEquals(listOf("f7", "faizabad", "saddar"), (published.json["points"] as List<*>).map { (it as Map<*, *>)["id"] })

        val passenger = signUp("Test Passenger", "3001110002")
        // Found from the pickup to a stop, but not backwards, and drivers don't see their own rides.
        assertTrue(get("/rides?from=f7&to=faizabad&date=2026-10-20", passenger).list.any { it["id"] == rideId })
        assertFalse(get("/rides?from=faizabad&to=f7&date=2026-10-20", passenger).list.any { it["id"] == rideId })
        assertFalse(get("/rides?from=f7&to=saddar&date=2026-10-20", driver).list.any { it["id"] == rideId })

        val booking = post("/rides/$rideId/bookings", mapOf("seats" to 1), passenger)
        assertEquals(201, booking.status, booking.body.toString())
        assertEquals("PENDING", booking.json["status"])
        val bookingId = booking.json["id"] as String
        assertEquals("ALREADY_REQUESTED", post("/rides/$rideId/bookings", mapOf("seats" to 1), passenger).code)

        val third = signUp("Third Rider", "3001110003")
        assertEquals("NO_SEATS", post("/rides/$rideId/bookings", mapOf("seats" to 2), third).code)

        assertEquals(403, post("/bookings/$bookingId/accept", token = passenger).status)
        assertEquals("CONFIRMED", post("/bookings/$bookingId/accept", token = driver).json["status"])
        val mine = get("/me/rides", driver).list.first { (it["ride"] as Map<*, *>)["id"] == rideId }
        assertEquals(1, (mine["passengers"] as List<*>).size)
        assertEquals(true, get("/me/bookings", passenger).list.first()["canCancel"])

        // 05:00 on the day: three hours before departure, so too late.
        clock.now = Instant.parse("2026-10-20T00:00:00Z")
        assertEquals(false, get("/me/bookings", passenger).list.first()["canCancel"])
        val late = post("/bookings/$bookingId/cancel", token = passenger)
        assertEquals(409, late.status)
        assertEquals("CANCEL_WINDOW_CLOSED", late.code)

        clock.now = TestInfra.START
        val cancelled = post("/bookings/$bookingId/cancel", token = passenger)
        assertEquals("CANCELLED", cancelled.json["status"])
        assertEquals("CANCELLED", cancelled.json["tripStatus"])
    }

    @Test
    fun `codes are rate limited, attempts are capped, and new numbers need a name`() {
        val number = mapOf("countryCode" to "+92", "number" to "3001110009")
        assertEquals(202, post("/auth/otp", number).status)
        val again = post("/auth/otp", number)
        assertEquals(429, again.status)
        assertNotNull(again.headers.getFirst(HttpHeaders.RETRY_AFTER))

        assertEquals("NAME_REQUIRED", post("/auth/verify", number + ("code" to "1234")).code)
        repeat(5) { assertEquals("INVALID_CODE", post("/auth/verify", number + mapOf("code" to "9999", "name" to "X")).code) }
        assertEquals("TOO_MANY_ATTEMPTS", post("/auth/verify", number + mapOf("code" to "1234", "name" to "X")).code)
    }

    @Test
    fun `the API needs a token except for sign-in and place search`() {
        assertEquals(401, get("/me").status)
        assertEquals(401, get("/rides?from=f7&to=saddar&date=2026-10-20").status)
        assertTrue(get("/places?q=saddar").list.any { it["id"] == "saddar" })
    }
}
