package dev.liftonway.backend.bookings

import dev.liftonway.backend.common.Conflict
import dev.liftonway.backend.common.Forbidden
import dev.liftonway.backend.common.LiftOnWayProperties
import dev.liftonway.backend.common.NotFound
import dev.liftonway.backend.common.userId
import dev.liftonway.backend.notifications.NotificationKind
import dev.liftonway.backend.notifications.Notifications
import dev.liftonway.backend.ratings.RatingPolicy
import dev.liftonway.backend.ratings.RatingRepository
import dev.liftonway.backend.rides.RideRecord
import dev.liftonway.backend.rides.RideRepository
import dev.liftonway.backend.rides.RideService
import dev.liftonway.backend.rides.RideStatus
import dev.liftonway.backend.rides.RideView
import dev.liftonway.backend.rides.TripStatus
import dev.liftonway.backend.vehicles.VehicleRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class BookingStatus { PENDING, CONFIRMED, DECLINED, CANCELLED }

/** The car as a passenger sees it. The number plate is shared only once their seat is confirmed. */
data class VehicleBrief(val name: String, val model: String, val colour: String, val airConditioning: Boolean, val heater: Boolean, val plate: String?)

data class BookingView(
    val id: UUID,
    val ride: RideView,
    val seats: Int,
    val status: BookingStatus,
    val tripStatus: TripStatus,
    val canCancel: Boolean,
    val vehicle: VehicleBrief?,
    /** Stars this passenger gave the driver, once rated. */
    val myRating: Int?,
    val canRate: Boolean,
)

data class RequestSeats(@field:Min(1) @field:Max(6) val seats: Int = 1)

/** The 4-hour rule, in one place: a passenger can cancel only while departure is at least the cutoff away. */
@Component
class CancellationPolicy(settings: LiftOnWayProperties) {
    private val cutoff: Duration = settings.cancellationCutoff
    val hours: Long get() = cutoff.toHours()

    fun canCancel(departure: Instant, now: Instant): Boolean = Duration.between(now, departure) >= cutoff
}

internal data class BookingRow(val id: UUID, val rideId: UUID, val passengerId: UUID, val seats: Int, val status: BookingStatus)

@Repository
class BookingRepository(private val jdbc: JdbcClient) {
    private fun map(rs: java.sql.ResultSet) = BookingRow(
        rs.getObject("id", UUID::class.java),
        rs.getObject("ride_id", UUID::class.java),
        rs.getObject("passenger_id", UUID::class.java),
        rs.getInt("seats"),
        BookingStatus.valueOf(rs.getString("status")),
    )

    internal fun find(id: UUID): BookingRow? = jdbc.sql("select * from bookings where id = :id").param("id", id).query { rs, _ -> map(rs) }.optional().orElse(null)

    internal fun byPassenger(passenger: UUID): List<BookingRow> =
        jdbc.sql("select * from bookings where passenger_id = :p order by created_at desc limit 100").param("p", passenger).query { rs, _ -> map(rs) }.list()

    fun hasLive(ride: UUID, passenger: UUID): Boolean = jdbc.sql(
        "select exists(select 1 from bookings where ride_id = :r and passenger_id = :p and status in ('PENDING', 'CONFIRMED'))",
    ).param("r", ride).param("p", passenger).query(Boolean::class.java).single()

    fun insert(id: UUID, ride: UUID, passenger: UUID, seats: Int, now: Instant) {
        jdbc.sql(
            """insert into bookings (id, ride_id, passenger_id, seats, status, created_at, updated_at)
               values (:id, :ride, :passenger, :seats, 'PENDING', :now, :now)""",
        ).param("id", id).param("ride", ride).param("passenger", passenger).param("seats", seats).param("now", Timestamp.from(now)).update()
    }

    fun setStatus(id: UUID, status: BookingStatus, now: Instant) {
        jdbc.sql("update bookings set status = :status, updated_at = :now where id = :id")
            .param("status", status.name).param("now", Timestamp.from(now)).param("id", id).update()
    }
}

@Service
class BookingService(
    private val bookings: BookingRepository,
    private val rides: RideRepository,
    private val rideService: RideService,
    private val policy: CancellationPolicy,
    private val notifications: Notifications,
    private val vehicles: VehicleRepository,
    private val ratings: RatingRepository,
    private val ratingPolicy: RatingPolicy,
    private val clock: Clock,
) {
    @Transactional
    fun request(rideId: UUID, passenger: UUID, seats: Int): BookingView {
        rides.lock(rideId)
        val record = rides.find(rideId) ?: throw NotFound("Ride")
        val ride = record.view
        if (!open(record)) throw Conflict("RIDE_UNAVAILABLE", "This ride is no longer available.")
        if (ride.driver.id == passenger) throw Conflict("OWN_RIDE", "You can't book a seat on your own ride.")
        if (bookings.hasLive(rideId, passenger)) throw Conflict("ALREADY_REQUESTED", "You've already asked for a seat on this ride.")
        if (ride.seatsLeft < seats) throw Conflict("NO_SEATS", "Not enough seats left on this ride.")
        val id = UUID.randomUUID()
        bookings.insert(id, rideId, passenger, seats, clock.instant())
        notifications.send(ride.driver.id, NotificationKind.RIDE_REQUEST, passenger, rideId, id)
        return view(bookings.find(id)!!)
    }

    fun mine(passenger: UUID): List<BookingView> = bookings.byPassenger(passenger).map(::view)

    @Transactional
    fun cancel(id: UUID, passenger: UUID): BookingView {
        val booking = bookings.find(id)?.takeIf { it.passengerId == passenger } ?: throw NotFound("Booking")
        if (booking.status !in setOf(BookingStatus.PENDING, BookingStatus.CONFIRMED)) throw Conflict("NOT_ACTIVE", "This request is no longer active.")
        val record = rides.find(booking.rideId)!!
        if (record.status != RideStatus.SCHEDULED) throw Conflict("NOT_ACTIVE", "This ride has already ended.")
        if (!policy.canCancel(record.view.departure.toInstant(), clock.instant())) {
            throw Conflict("CANCEL_WINDOW_CLOSED", "Ride can only be canceled before ${policy.hours} hours.")
        }
        bookings.setStatus(id, BookingStatus.CANCELLED, clock.instant())
        notifications.send(record.view.driver.id, NotificationKind.REQUEST_WITHDRAWN, passenger, record.view.id, id)
        return view(bookings.find(id)!!)
    }

    /** The ride's driver accepts or declines a pending request. */
    @Transactional
    fun answer(id: UUID, driver: UUID, accept: Boolean): BookingView {
        val booking = bookings.find(id) ?: throw NotFound("Booking")
        val record = rides.find(booking.rideId)!!
        if (record.view.driver.id != driver) throw Forbidden("Only the ride's driver can answer this request.")
        if (booking.status != BookingStatus.PENDING) throw Conflict("NOT_PENDING", "This request has already been answered.")
        if (!open(record)) throw Conflict("RIDE_UNAVAILABLE", "This ride has already set off or ended.")
        bookings.setStatus(id, if (accept) BookingStatus.CONFIRMED else BookingStatus.DECLINED, clock.instant())
        val kind = if (accept) NotificationKind.REQUEST_ACCEPTED else NotificationKind.REQUEST_DECLINED
        notifications.send(booking.passengerId, kind, driver, record.view.id, id)
        return view(bookings.find(id)!!)
    }

    /** Still taking requests and answers: scheduled and not yet departed. */
    private fun open(record: RideRecord) =
        record.status == RideStatus.SCHEDULED && record.view.departure.toInstant().isAfter(clock.instant())

    private fun view(b: BookingRow): BookingView {
        val (ride, status) = rides.find(b.rideId)!!
        val live = b.status == BookingStatus.PENDING || b.status == BookingStatus.CONFIRMED
        val trip = if (!live) TripStatus.CANCELLED else rideService.tripStatus(status)
        val canCancel = live && trip == TripStatus.UPCOMING && policy.canCancel(ride.departure.toInstant(), clock.instant())
        val vehicle = vehicles.find(ride.driver.id)?.let {
            VehicleBrief(it.name, it.model, it.colour, it.airConditioning, it.heater, plate = it.plate.takeIf { b.status == BookingStatus.CONFIRMED })
        }
        val myRating = ratings.given(b.passengerId, listOf(b.id))[b.id]
        val canRate = myRating == null && ratingPolicy.open(status, b.status, ride.departure.toInstant())
        return BookingView(b.id, ride, b.seats, b.status, trip, canCancel, vehicle, myRating, canRate)
    }
}

@RestController
@RequestMapping("/api/v1")
class BookingsController(private val service: BookingService) {
    @PostMapping("/rides/{rideId}/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    fun request(@AuthenticationPrincipal jwt: Jwt, @PathVariable rideId: UUID, @Valid @RequestBody body: RequestSeats): BookingView =
        service.request(rideId, jwt.userId, body.seats)

    @GetMapping("/me/bookings")
    fun mine(@AuthenticationPrincipal jwt: Jwt): List<BookingView> = service.mine(jwt.userId)

    @PostMapping("/bookings/{id}/cancel")
    fun cancel(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID): BookingView = service.cancel(id, jwt.userId)

    @PostMapping("/bookings/{id}/accept")
    fun accept(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID): BookingView = service.answer(id, jwt.userId, accept = true)

    @PostMapping("/bookings/{id}/decline")
    fun decline(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID): BookingView = service.answer(id, jwt.userId, accept = false)
}
