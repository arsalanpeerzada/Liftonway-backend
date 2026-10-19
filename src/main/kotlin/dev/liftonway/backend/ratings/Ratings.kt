package dev.liftonway.backend.ratings

import dev.liftonway.backend.bookings.BookingRepository
import dev.liftonway.backend.bookings.BookingStatus
import dev.liftonway.backend.common.Conflict
import dev.liftonway.backend.common.NotFound
import dev.liftonway.backend.common.userId
import dev.liftonway.backend.rides.RideRepository
import dev.liftonway.backend.rides.RideStatus
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

data class RateBody(@field:Min(1) @field:Max(5) val stars: Int)

data class RatingView(val bookingId: UUID, val stars: Int)

/** When a seat can be rated: a confirmed seat on a completed ride, for two weeks after departure. */
@Component
class RatingPolicy(private val clock: Clock) {
    val window: Duration = Duration.ofDays(14)

    fun open(rideStatus: RideStatus, bookingStatus: BookingStatus, departure: Instant): Boolean =
        rideStatus == RideStatus.COMPLETED && bookingStatus == BookingStatus.CONFIRMED && clock.instant().isBefore(departure.plus(window))
}

@Repository
class RatingRepository(private val jdbc: JdbcClient) {
    /** Stars [rater] gave on each of [bookings], where they have rated. */
    fun given(rater: UUID, bookings: Collection<UUID>): Map<UUID, Int> {
        if (bookings.isEmpty()) return emptyMap()
        return jdbc.sql("select booking_id, stars from ratings where rater_id = :rater and booking_id in (:ids)")
            .param("rater", rater).param("ids", bookings)
            .query { rs, _ -> rs.getObject("booking_id", UUID::class.java) to rs.getInt("stars") }
            .list().toMap()
    }

    /** False when [rater] already rated this booking. */
    fun add(booking: UUID, rater: UUID, ratee: UUID, stars: Int, now: Instant): Boolean = jdbc.sql(
        """insert into ratings (booking_id, rater_id, ratee_id, stars, created_at) values (:b, :rater, :ratee, :stars, :now)
           on conflict do nothing""",
    ).param("b", booking).param("rater", rater).param("ratee", ratee).param("stars", stars).param("now", Timestamp.from(now)).update() > 0

    /** Recomputes the average shown next to [user]'s name. */
    fun refreshAverage(user: UUID) {
        jdbc.sql(
            """update users set rating = s.avg, rating_count = s.n
               from (select round(avg(stars), 1) as avg, count(*) as n from ratings where ratee_id = :user) s
               where id = :user""",
        ).param("user", user).update()
    }
}

@Service
class RatingService(
    private val ratings: RatingRepository,
    private val bookings: BookingRepository,
    private val rides: RideRepository,
    private val policy: RatingPolicy,
    private val clock: Clock,
) {
    /** The passenger rates the driver, or the driver rates the passenger, depending on who calls. */
    @Transactional
    fun rate(bookingId: UUID, rater: UUID, stars: Int): RatingView {
        val booking = bookings.find(bookingId) ?: throw NotFound("Booking")
        val ride = rides.find(booking.rideId)!!
        val ratee = when (rater) {
            booking.passengerId -> ride.view.driver.id
            ride.view.driver.id -> booking.passengerId
            else -> throw NotFound("Booking")
        }
        if (!policy.open(ride.status, booking.status, ride.view.departure.toInstant())) {
            val closed = ride.status == RideStatus.COMPLETED && booking.status == BookingStatus.CONFIRMED
            throw if (closed) {
                Conflict("RATING_CLOSED", "Ratings close ${policy.window.toDays()} days after the ride.")
            } else {
                Conflict("NOT_RATEABLE", "You can rate once the ride is completed.")
            }
        }
        if (!ratings.add(bookingId, rater, ratee, stars, clock.instant())) throw Conflict("ALREADY_RATED", "You've already rated this ride.")
        ratings.refreshAverage(ratee)
        return RatingView(bookingId, stars)
    }
}

@RestController
@RequestMapping("/api/v1")
class RatingsController(private val service: RatingService) {
    @PostMapping("/bookings/{id}/rating")
    @ResponseStatus(HttpStatus.CREATED)
    fun rate(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @Valid @RequestBody body: RateBody): RatingView =
        service.rate(id, jwt.userId, body.stars)
}
