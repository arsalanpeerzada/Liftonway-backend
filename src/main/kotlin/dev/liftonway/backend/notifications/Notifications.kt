package dev.liftonway.backend.notifications

import dev.liftonway.backend.common.LiftOnWayProperties
import dev.liftonway.backend.common.NotFound
import dev.liftonway.backend.common.userId
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

enum class NotificationKind {
    /** To a driver: someone asked for a seat. Answered through the booking's accept and decline endpoints. */
    RIDE_REQUEST,
    REQUEST_ACCEPTED,
    REQUEST_DECLINED,

    /** To a driver: a passenger cancelled their request. */
    REQUEST_WITHDRAWN,
    RIDE_CANCELLED,
    RIDE_COMPLETED,
}

data class Actor(val id: UUID, val name: String, val photoUrl: String?)

data class RideBrief(val id: UUID, val from: String, val to: String, val departure: OffsetDateTime)

/**
 * One entry in the user's list. [actionable] is true for a seat request the driver can still answer;
 * [bookingStatus] lets the app say how an older request ended.
 */
data class NotificationView(
    val id: UUID,
    val kind: NotificationKind,
    val actor: Actor?,
    val ride: RideBrief?,
    val bookingId: UUID?,
    val seats: Int?,
    val bookingStatus: String?,
    val actionable: Boolean,
    val read: Boolean,
    val createdAt: OffsetDateTime,
)

data class UnreadCount(val count: Int)

@Repository
class Notifications(private val jdbc: JdbcClient, private val settings: LiftOnWayProperties, private val clock: Clock) {

    /** Adds a notification for [to]. Call inside the transaction that made the change, so both land or neither does. */
    fun send(to: UUID, kind: NotificationKind, actor: UUID?, ride: UUID?, booking: UUID? = null) {
        jdbc.sql(
            """insert into notifications (id, user_id, kind, actor_id, ride_id, booking_id, created_at)
               values (:id, :to, :kind, :actor, :ride, :booking, :now)""",
        )
            .param("id", UUID.randomUUID()).param("to", to).param("kind", kind.name).param("actor", actor)
            .param("ride", ride).param("booking", booking).param("now", Timestamp.from(clock.instant()))
            .update()
    }

    fun list(user: UUID, limit: Int = 100): List<NotificationView> = jdbc.sql(
        """select n.*, a.name as actor_name, a.photo_url as actor_photo,
                  r.departure, r.status as ride_status, b.status as booking_status, b.seats,
                  (select p.title from ride_points rp join places p on p.id = rp.place_id
                    where rp.ride_id = n.ride_id order by rp.position limit 1) as from_title,
                  (select p.title from ride_points rp join places p on p.id = rp.place_id
                    where rp.ride_id = n.ride_id order by rp.position desc limit 1) as to_title
           from notifications n
           left join users a on a.id = n.actor_id
           left join rides r on r.id = n.ride_id
           left join bookings b on b.id = n.booking_id
           where n.user_id = :user
           order by n.created_at desc limit :limit""",
    ).param("user", user).param("limit", limit).query { rs, _ -> view(rs) }.list()

    fun unread(user: UUID): Int =
        jdbc.sql("select count(*) from notifications where user_id = :user and read_at is null").param("user", user).query(Int::class.java).single()

    fun markAllRead(user: UUID) {
        jdbc.sql("update notifications set read_at = :now where user_id = :user and read_at is null")
            .param("now", Timestamp.from(clock.instant())).param("user", user).update()
    }

    fun delete(user: UUID, id: UUID): Boolean =
        jdbc.sql("delete from notifications where id = :id and user_id = :user").param("id", id).param("user", user).update() > 0

    /** Drops notifications older than [age]. Returns how many went. */
    fun purgeOlderThan(age: Duration): Int =
        jdbc.sql("delete from notifications where created_at < :before").param("before", Timestamp.from(clock.instant().minus(age))).update()

    private fun view(rs: ResultSet): NotificationView {
        val kind = NotificationKind.valueOf(rs.getString("kind"))
        val actorId = rs.getObject("actor_id", UUID::class.java)
        val rideId = rs.getObject("ride_id", UUID::class.java)
        val departure = rs.getTimestamp("departure")?.toInstant()
        val bookingStatus = rs.getString("booking_status")
        val actionable = kind == NotificationKind.RIDE_REQUEST && bookingStatus == "PENDING" &&
            rs.getString("ride_status") == "SCHEDULED" && departure != null && departure.isAfter(clock.instant())
        return NotificationView(
            id = rs.getObject("id", UUID::class.java),
            kind = kind,
            actor = actorId?.let { Actor(it, rs.getString("actor_name"), rs.getString("actor_photo")) },
            ride = if (rideId != null && departure != null) {
                RideBrief(rideId, rs.getString("from_title"), rs.getString("to_title"), departure.atZone(settings.zone).toOffsetDateTime())
            } else {
                null
            },
            bookingId = rs.getObject("booking_id", UUID::class.java),
            seats = rs.getObject("seats") as Int?,
            bookingStatus = bookingStatus,
            actionable = actionable,
            read = rs.getTimestamp("read_at") != null,
            createdAt = rs.getTimestamp("created_at").toInstant().atZone(settings.zone).toOffsetDateTime(),
        )
    }
}

@RestController
@RequestMapping("/api/v1/me/notifications")
class NotificationsController(private val notifications: Notifications) {
    /** Newest first, at most 100. */
    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt): List<NotificationView> = notifications.list(jwt.userId)

    @GetMapping("/unread")
    fun unread(@AuthenticationPrincipal jwt: Jwt) = UnreadCount(notifications.unread(jwt.userId))

    @PostMapping("/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun markAllRead(@AuthenticationPrincipal jwt: Jwt) = notifications.markAllRead(jwt.userId)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) {
        if (!notifications.delete(jwt.userId, id)) throw NotFound("Notification")
    }
}
