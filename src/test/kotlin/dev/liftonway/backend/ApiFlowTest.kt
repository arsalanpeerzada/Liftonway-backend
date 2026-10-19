package dev.liftonway.backend

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestClient
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import javax.sql.DataSource
import dev.liftonway.backend.rides.RideService
import java.time.Clock
import java.time.Duration
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
    /** A real, throwaway PostgreSQL for this test run, started in-process. */
    @Bean(destroyMethod = "close")
    fun postgres(): EmbeddedPostgres = EmbeddedPostgres.start()

    @Bean
    fun dataSource(postgres: EmbeddedPostgres): DataSource = postgres.postgresDatabase

    @Bean
    @Primary
    fun testClock() = MutableClock(START)

    companion object {
        /** Sunday 19 October 2026, 10:00 in Pakistan. */
        val START: Instant = Instant.parse("2026-10-19T05:00:00Z")
    }
}

/** The core journey end to end against a real PostgreSQL (embedded), through the HTTP API the app will call. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestInfra::class)
class ApiFlowTest {
    @LocalServerPort var port = 0

    @Autowired lateinit var clock: MutableClock

    @Autowired lateinit var rides: RideService

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
    fun `drivers answer requests from notifications, and rides are cancelled or completed`() {
        val driver = signUp("Lifecycle Driver", "3001120001")
        val car = mapOf("name" to "Toyota Yaris", "model" to "2023", "plate" to "ABC 123", "colour" to "Grey", "airConditioning" to true, "heater" to true)
        put("/me/vehicle", car, driver)
        fun publish(departure: String) = post(
            "/rides",
            mapOf("from" to "blue", "to" to "airport", "departure" to departure, "seats" to 3, "pricePerSeat" to 600),
            driver,
        ).json["id"] as String

        val rideId = publish("2026-10-20T08:00:00+05:00")
        val asma = signUp("Asma Rider", "3001120002")
        val omar = signUp("Omar Rider", "3001120003")
        val asmaBooking = post("/rides/$rideId/bookings", mapOf("seats" to 1), asma).json["id"] as String
        clock.now = clock.now.plusSeconds(60)
        val omarBooking = post("/rides/$rideId/bookings", mapOf("seats" to 1), omar).json["id"] as String

        // The driver sees both requests, newest first, ready to answer.
        assertEquals(2, get("/me/notifications/unread", driver).json["count"])
        val requests = get("/me/notifications", driver).list
        assertEquals(listOf("RIDE_REQUEST", "RIDE_REQUEST"), requests.map { it["kind"] })
        assertEquals("Omar Rider", (requests.first()["actor"] as Map<*, *>)["name"])
        assertEquals("Blue Area, Jinnah Avenue", (requests.first()["ride"] as Map<*, *>)["from"])
        assertEquals("Islamabad International Airport", (requests.first()["ride"] as Map<*, *>)["to"])
        assertTrue(requests.all { it["actionable"] == true })

        assertEquals("DECLINED", post("/bookings/$omarBooking/decline", token = driver).json["status"])
        assertEquals("CONFIRMED", post("/bookings/$asmaBooking/accept", token = driver).json["status"])
        val answered = get("/me/notifications", driver).list
        assertTrue(answered.none { it["actionable"] == true })
        assertEquals(setOf("DECLINED", "CONFIRMED"), answered.map { it["bookingStatus"] }.toSet())
        assertEquals("REQUEST_DECLINED", get("/me/notifications", omar).list.single()["kind"])
        assertEquals("REQUEST_ACCEPTED", get("/me/notifications", asma).list.single()["kind"])

        post("/me/notifications/read", token = driver)
        assertEquals(0, get("/me/notifications/unread", driver).json["count"])
        val gone = answered.first()["id"]
        assertEquals(404, call(HttpMethod.DELETE, "/me/notifications/$gone", token = asma).status)
        assertEquals(204, call(HttpMethod.DELETE, "/me/notifications/$gone", token = driver).status)
        assertEquals(1, get("/me/notifications", driver).list.size)

        // With a confirmed passenger, the driver is held to the 4-hour rule too, and can't complete before leaving.
        clock.now = Instant.parse("2026-10-20T00:00:00Z")
        fun mine(id: String) = get("/me/rides", driver).list.first { (it["ride"] as Map<*, *>)["id"] == id }
        assertEquals(false, mine(rideId)["canCancel"])
        assertEquals("CANCEL_WINDOW_CLOSED", post("/rides/$rideId/cancel", token = driver).code)
        assertEquals("NOT_DEPARTED", post("/rides/$rideId/complete", token = driver).code)
        assertEquals(403, post("/rides/$rideId/complete", token = asma).status)

        clock.now = Instant.parse("2026-10-20T04:00:00Z")
        assertEquals(true, mine(rideId)["canComplete"])
        assertEquals("COMPLETED", post("/rides/$rideId/complete", token = driver).json["tripStatus"])
        assertEquals("RIDE_ENDED", post("/rides/$rideId/complete", token = driver).code)
        assertEquals("COMPLETED", get("/me/bookings", asma).list.first()["tripStatus"])
        assertEquals("RIDE_COMPLETED", get("/me/notifications", asma).list.first()["kind"])

        // Both sides rate each other once; the driver's average updates. Declined passengers can't rate.
        val asmaTrip = get("/me/bookings", asma).list.first { it["id"] == asmaBooking }
        assertEquals(true, asmaTrip["canRate"])
        assertEquals("ABC 123", (asmaTrip["vehicle"] as Map<*, *>)["plate"])
        assertEquals(null, (get("/me/bookings", omar).list.first { it["id"] == omarBooking }["vehicle"] as Map<*, *>)["plate"])
        assertEquals(201, post("/bookings/$asmaBooking/rating", mapOf("stars" to 4), asma).status)
        assertEquals("ALREADY_RATED", post("/bookings/$asmaBooking/rating", mapOf("stars" to 5), asma).code)
        assertEquals("NOT_RATEABLE", post("/bookings/$omarBooking/rating", mapOf("stars" to 1), omar).code)
        assertEquals(400, post("/bookings/$asmaBooking/rating", mapOf("stars" to 6), driver).status)
        assertEquals(201, post("/bookings/$asmaBooking/rating", mapOf("stars" to 5), driver).status)
        assertEquals(4.0, get("/me", driver).json["rating"])
        assertEquals(5.0, get("/me", asma).json["rating"])
        assertEquals(4, get("/me/bookings", asma).list.first { it["id"] == asmaBooking }["myRating"])
        val rated = (mine(rideId)["passengers"] as List<*>).single() as Map<*, *>
        assertEquals(5, rated["myRating"])
        assertEquals(false, rated["canRate"])

        // A ride nobody is confirmed on can be called off late; whoever asked hears about it.
        clock.now = TestInfra.START
        val second = publish("2026-10-21T08:00:00+05:00")
        post("/rides/$second/bookings", mapOf("seats" to 2), omar)
        clock.now = Instant.parse("2026-10-21T02:00:00Z")
        assertEquals("CANCELLED", post("/rides/$second/cancel", token = driver).json["tripStatus"])
        assertEquals("RIDE_CANCELLED", get("/me/notifications", omar).list.first()["kind"])
        assertEquals("CANCELLED", get("/me/bookings", omar).list.first { (it["ride"] as Map<*, *>)["id"] == second }["tripStatus"])

        // Rides the driver forgets are completed six hours after departure, and unanswered requests lapse.
        clock.now = TestInfra.START
        val third = publish("2026-10-22T08:00:00+05:00")
        val waiting = post("/rides/$third/bookings", mapOf("seats" to 1), asma).json["id"] as String
        clock.now = Instant.parse("2026-10-22T08:30:00Z")
        rides.completeOverdue(Duration.ofHours(6))
        assertEquals("UPCOMING", mine(third)["tripStatus"])
        clock.now = Instant.parse("2026-10-22T09:30:00Z")
        rides.completeOverdue(Duration.ofHours(6))
        assertEquals("COMPLETED", mine(third)["tripStatus"])
        assertEquals("DECLINED", get("/me/bookings", asma).list.first { it["id"] == waiting }["status"])
    }

    @Test
    fun `access tokens expire, refresh tokens rotate, and a reused refresh token ends the sign-in`() {
        val number = mapOf("countryCode" to "+92", "number" to "3001130001")
        post("/auth/otp", number)
        val session = post("/auth/verify", number + mapOf("code" to "1234", "name" to "Rotating Rider")).json
        val first = session["refreshToken"] as String

        // The test profile stretches access tokens to 30 days so other tests can move the clock freely.
        clock.now = clock.now.plus(Duration.ofDays(31))
        assertEquals(401, get("/me", session["token"] as String).status)

        val renewed = post("/auth/refresh", mapOf("refreshToken" to first))
        assertEquals(200, renewed.status, renewed.body.toString())
        val second = renewed.json["refreshToken"] as String
        assertEquals(200, get("/me", renewed.json["token"] as String).status)

        // Someone replays the spent token: the attempt fails and the legitimate successor dies with it.
        assertEquals("INVALID_REFRESH", post("/auth/refresh", mapOf("refreshToken" to first)).code)
        assertEquals("INVALID_REFRESH", post("/auth/refresh", mapOf("refreshToken" to second)).code)
        assertEquals("INVALID_REFRESH", post("/auth/refresh", mapOf("refreshToken" to "not-a-token")).code)

        // Signing out ends that device's sign-in, and repeating it is harmless.
        post("/auth/otp", number.plus("number" to "3001130002"))
        val other = post("/auth/verify", mapOf("countryCode" to "+92", "number" to "3001130002", "code" to "1234", "name" to "Other")).json
        val token = other["refreshToken"] as String
        assertEquals(204, post("/auth/logout", mapOf("refreshToken" to token)).status)
        assertEquals(204, post("/auth/logout", mapOf("refreshToken" to token)).status)
        assertEquals("INVALID_REFRESH", post("/auth/refresh", mapOf("refreshToken" to token)).code)
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

        // Client mistakes are 4xx with a code, never a 500.
        val token = signUp("Careless Client", "3001140001")
        assertEquals("NOT_FOUND", get("/no-such-thing", token).code)
        assertEquals("BAD_REQUEST", get("/rides?from=f7&to=saddar&date=someday", token).code)
        assertEquals("BAD_REQUEST", get("/rides?to=saddar&date=2026-10-20", token).code)
        assertEquals(405, call(HttpMethod.DELETE, "/rides", token = token).status)
    }
}
