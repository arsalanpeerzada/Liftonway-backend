package dev.liftonway.backend.rides

import dev.liftonway.backend.common.Conflict
import dev.liftonway.backend.common.Invalid
import dev.liftonway.backend.common.LiftOnWayProperties
import dev.liftonway.backend.common.NotFound
import dev.liftonway.backend.common.userId
import dev.liftonway.backend.places.Place
import dev.liftonway.backend.places.PlaceRepository
import dev.liftonway.backend.vehicles.VehicleRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class Driver(val id: UUID, val name: String, val rating: Double?, val photoUrl: String?)

/** A ride as the app shows it. [points] runs pickup first, then any stops, then the destination. */
data class RideView(
    val id: UUID,
    val driver: Driver,
    val departure: OffsetDateTime,
    val points: List<Place>,
    val vehicleName: String,
    val seatsTotal: Int,
    val seatsLeft: Int,
    val pricePerSeat: Int,
    val route: String,
)

enum class TripStatus { UPCOMING, COMPLETED, CANCELLED }

data class PublishRide(
    @field:NotBlank val from: String,
    @field:NotBlank val to: String,
    @field:Size(max = 3) val stops: List<String> = emptyList(),
    val departure: OffsetDateTime,
    @field:Min(1) @field:Max(6) val seats: Int,
    @field:Min(50) @field:Max(50_000) val pricePerSeat: Int,
    @field:Size(max = 200) val route: String = "",
)

data class Passenger(val bookingId: UUID, val name: String, val seats: Int, val status: String)

data class PublishedRideView(val ride: RideView, val tripStatus: TripStatus, val passengers: List<Passenger>)

/** Rides, their ordered points and how many seats are still free. Seats count as taken while pending or confirmed. */
@Repository
class RideRepository(private val jdbc: JdbcClient, private val settings: LiftOnWayProperties) {
    private val select = """
        select r.*, u.name as driver_name, u.rating as driver_rating, u.photo_url as driver_photo,
               r.seats_total - coalesce((select sum(b.seats) from bookings b
                                         where b.ride_id = r.id and b.status in ('PENDING', 'CONFIRMED')), 0) as seats_left
        from rides r join users u on u.id = r.driver_id
    """.trimIndent()

    private data class Row(val view: RideView, val cancelled: Boolean)

    private fun row(rs: ResultSet) = Row(
        RideView(
            id = rs.getObject("id", UUID::class.java),
            driver = Driver(
                rs.getObject("driver_id", UUID::class.java),
                rs.getString("driver_name"),
                rs.getObject("driver_rating", BigDecimal::class.java)?.toDouble(),
                rs.getString("driver_photo"),
            ),
            departure = rs.getTimestamp("departure").toInstant().atZone(settings.zone).toOffsetDateTime(),
            points = emptyList(),
            vehicleName = rs.getString("vehicle_name"),
            seatsTotal = rs.getInt("seats_total"),
            seatsLeft = rs.getInt("seats_left"),
            pricePerSeat = rs.getInt("price_per_seat"),
            route = rs.getString("route"),
        ),
        rs.getBoolean("cancelled"),
    )

    /** Fills in each ride's points with one query. */
    private fun withPoints(rows: List<Row>): List<Pair<RideView, Boolean>> {
        if (rows.isEmpty()) return emptyList()
        val points = jdbc.sql(
            """select rp.ride_id, p.id, p.title, p.area from ride_points rp join places p on p.id = rp.place_id
               where rp.ride_id in (:ids) order by rp.ride_id, rp.position""",
        )
            .param("ids", rows.map { it.view.id })
            .query { rs, _ -> rs.getObject("ride_id", UUID::class.java) to Place(rs.getString("id"), rs.getString("title"), rs.getString("area")) }
            .list()
            .groupBy({ it.first }, { it.second })
        return rows.map { it.view.copy(points = points[it.view.id].orEmpty()) to it.cancelled }
    }

    fun find(id: UUID): Pair<RideView, Boolean>? =
        withPoints(jdbc.sql("$select where r.id = :id").param("id", id).query { rs, _ -> row(rs) }.list()).firstOrNull()

    /** Locks the ride row so concurrent seat requests are checked one at a time. */
    fun lock(id: UUID) {
        jdbc.sql("select id from rides where id = :id for update").param("id", id).query(UUID::class.java).optional()
    }

    /**
     * Rides that pass [from] before [to] (as pickup, stop or destination), leave on [date] in Pakistan time,
     * at least [earliest], with [seats] free, and aren't the searcher's own.
     */
    fun search(from: String, to: String, date: LocalDate, seats: Int, earliest: Instant, me: UUID, limit: Int = 50): List<RideView> {
        val dayStart = date.atStartOfDay(settings.zone).toInstant()
        val rows = jdbc.sql(
            """select * from ($select
                 join ride_points a on a.ride_id = r.id and a.place_id = :from
                 join ride_points b on b.ride_id = r.id and b.place_id = :to and b.position > a.position
                 where not r.cancelled and r.driver_id <> :me
                   and r.departure >= :dayStart and r.departure < :dayEnd and r.departure >= :earliest) s
               where s.seats_left >= :seats
               order by s.departure limit :limit""",
        )
            .param("from", from).param("to", to).param("me", me).param("seats", seats).param("limit", limit)
            .param("dayStart", Timestamp.from(dayStart)).param("dayEnd", Timestamp.from(date.plusDays(1).atStartOfDay(settings.zone).toInstant()))
            .param("earliest", Timestamp.from(maxOf(earliest, dayStart)))
            .query { rs, _ -> row(rs) }.list()
        return withPoints(rows).map { it.first }
    }

    fun byDriver(driver: UUID): List<Pair<RideView, Boolean>> =
        withPoints(jdbc.sql("$select where r.driver_id = :driver order by r.departure desc limit 100").param("driver", driver).query { rs, _ -> row(rs) }.list())

    fun create(id: UUID, driver: UUID, departure: Instant, points: List<String>, seats: Int, price: Int, route: String, vehicleName: String, now: Instant) {
        jdbc.sql(
            """insert into rides (id, driver_id, departure, seats_total, price_per_seat, route, vehicle_name, created_at)
               values (:id, :driver, :departure, :seats, :price, :route, :vehicle, :now)""",
        )
            .param("id", id).param("driver", driver).param("departure", Timestamp.from(departure)).param("seats", seats)
            .param("price", price).param("route", route).param("vehicle", vehicleName).param("now", Timestamp.from(now))
            .update()
        points.forEachIndexed { i, place ->
            jdbc.sql("insert into ride_points (ride_id, position, place_id) values (:id, :pos, :place)")
                .param("id", id).param("pos", i).param("place", place).update()
        }
    }

    fun passengers(rideId: UUID): List<Passenger> = jdbc.sql(
        """select b.id, u.name, b.seats, b.status from bookings b join users u on u.id = b.passenger_id
           where b.ride_id = :id and b.status in ('PENDING', 'CONFIRMED') order by b.created_at""",
    )
        .param("id", rideId)
        .query { rs, _ -> Passenger(rs.getObject("id", UUID::class.java), rs.getString("name"), rs.getInt("seats"), rs.getString("status")) }
        .list()
}

@Service
class RideService(
    private val rides: RideRepository,
    private val places: PlaceRepository,
    private val vehicles: VehicleRepository,
    private val clock: Clock,
) {
    /** Searches start at least this far ahead, so nobody books a car that is already pulling away. */
    private val searchLead = Duration.ofMinutes(30)

    fun search(from: String, to: String, date: LocalDate, seats: Int, me: UUID): List<RideView> {
        if (from == to) throw Invalid("SAME_PLACE", "Pickup and destination must differ.")
        return rides.search(from, to, date, seats, clock.instant().plus(searchLead), me)
    }

    /** Rides on the same route on other days this week, one per driver, for when the chosen day has none. */
    fun recommended(from: String, to: String, excluding: LocalDate, seats: Int, me: UUID, zone: java.time.ZoneId): List<RideView> {
        val today = clock.instant().atZone(zone).toLocalDate()
        return (0L..6L).map { today.plusDays(it) }.filter { it != excluding }
            .flatMap { rides.search(from, to, it, seats, clock.instant().plus(searchLead), me, limit = 5) }
            .distinctBy { it.driver.id }
            .take(3)
    }

    @Transactional
    fun publish(driver: UUID, body: PublishRide): RideView {
        val vehicle = vehicles.find(driver) ?: throw Conflict("VEHICLE_REQUIRED", "Add your vehicle before publishing a ride.")
        val points = listOf(body.from) + body.stops + body.to
        if (points.toSet().size != points.size) throw Invalid("DUPLICATE_PLACE", "Pickup, stops and destination must all differ.")
        if (!places.exists(points)) throw Invalid("UNKNOWN_PLACE", "One of the places isn't recognised.")
        val now = clock.instant()
        val departure = body.departure.toInstant()
        if (departure.isBefore(now.plus(searchLead))) throw Invalid("DEPARTURE_TOO_SOON", "Departure must be at least 30 minutes from now.")
        if (departure.isAfter(now.plus(Duration.ofDays(60)))) throw Invalid("DEPARTURE_TOO_FAR", "Rides can be published up to 60 days ahead.")
        val id = UUID.randomUUID()
        rides.create(id, driver, departure, points, body.seats, body.pricePerSeat, body.route.trim(), vehicle.name, now)
        return rides.find(id)!!.first
    }

    fun tripStatus(ride: RideView, cancelled: Boolean): TripStatus = when {
        cancelled -> TripStatus.CANCELLED
        ride.departure.toInstant().isBefore(clock.instant()) -> TripStatus.COMPLETED
        else -> TripStatus.UPCOMING
    }
}

@RestController
@RequestMapping("/api/v1")
class RidesController(private val service: RideService, private val rides: RideRepository, private val settings: LiftOnWayProperties) {

    @GetMapping("/rides")
    fun search(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam from: String,
        @RequestParam to: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @RequestParam(defaultValue = "1") seats: Int,
    ): List<RideView> = service.search(from, to, date, seats.coerceIn(1, 6), jwt.userId)

    @GetMapping("/rides/recommended")
    fun recommended(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam from: String,
        @RequestParam to: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) excluding: LocalDate,
        @RequestParam(defaultValue = "1") seats: Int,
    ): List<RideView> = service.recommended(from, to, excluding, seats.coerceIn(1, 6), jwt.userId, settings.zone)

    @GetMapping("/rides/{id}")
    fun get(@PathVariable id: UUID): RideView = rides.find(id)?.first ?: throw NotFound("Ride")

    @PostMapping("/rides")
    @ResponseStatus(HttpStatus.CREATED)
    fun publish(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody body: PublishRide): RideView = service.publish(jwt.userId, body)

    /** The signed-in driver's rides, newest first, with who has asked for a seat. */
    @GetMapping("/me/rides")
    fun mine(@AuthenticationPrincipal jwt: Jwt): List<PublishedRideView> =
        rides.byDriver(jwt.userId).map { (ride, cancelled) -> PublishedRideView(ride, service.tripStatus(ride, cancelled), rides.passengers(ride.id)) }
}
