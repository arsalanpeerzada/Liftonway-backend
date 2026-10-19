package dev.liftonway.backend.push

import dev.liftonway.backend.common.userId
import dev.liftonway.backend.notifications.NotificationCreated
import dev.liftonway.backend.notifications.NotificationKind
import dev.liftonway.backend.notifications.NotificationView
import dev.liftonway.backend.notifications.Notifications
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.sql.Timestamp
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** What a phone shows. Sent as data, so the app builds the notification itself (channel, tap action, privacy). */
data class PushMessage(val title: String, val body: String, val data: Map<String, String>)

/** Delivers to one install. Returns false when the token is dead and should be forgotten. */
fun interface PushSender {
    fun send(deviceToken: String, message: PushMessage): Boolean
}

@Configuration
@EnableAsync
class PushConfig {
    /**
     * FCM when `liftonway.push.fcm.credentials-file` points at a service account key; otherwise (local development)
     * pushes are skipped and the in-app list still works.
     */
    @Bean
    fun pushSender(props: FcmProperties, clock: Clock): PushSender {
        if (props.credentialsFile.isNotBlank()) return FcmPushSender(props, clock)
        LoggerFactory.getLogger(PushConfig::class.java).info("Push notifications are off: no Firebase credentials configured.")
        return PushSender { _, _ -> true }
    }
}

data class DeviceBody(@field:NotBlank @field:Size(max = 4096) val token: String)

@Repository
class Devices(private val jdbc: JdbcClient, private val clock: Clock) {
    /** Registers [token] for [user]. A token that belonged to someone else (shared phone) moves over. */
    fun register(user: UUID, token: String) {
        jdbc.sql(
            """insert into device_tokens (token, user_id, updated_at) values (:token, :user, :now)
               on conflict (token) do update set user_id = excluded.user_id, updated_at = excluded.updated_at""",
        ).param("token", token).param("user", user).param("now", Timestamp.from(clock.instant())).update()
    }

    fun forget(token: String) {
        jdbc.sql("delete from device_tokens where token = :token").param("token", token).update()
    }

    fun of(user: UUID): List<String> =
        jdbc.sql("select token from device_tokens where user_id = :user").param("user", user).query { rs, _ -> rs.getString(1) }.list()
}

@RestController
class DevicesController(private val devices: Devices) {
    /** The app calls this after sign-in and whenever Firebase gives it a new token. */
    @PutMapping("/api/v1/me/device")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun register(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody body: DeviceBody) = devices.register(jwt.userId, body.token)
}

/**
 * Pushes each new notification to the recipient's phones, after the change that caused it has committed
 * and off the request thread, so a slow or failing push never affects the API call.
 */
@Component
class PushNotifier(private val notifications: Notifications, private val devices: Devices, private val sender: PushSender) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(event: NotificationCreated) {
        val tokens = devices.of(event.userId)
        if (tokens.isEmpty()) return
        val note = notifications.find(event.id) ?: return
        val message = message(note)
        tokens.forEach { token ->
            val alive = try {
                sender.send(token, message)
            } catch (e: RuntimeException) {
                log.warn("Push failed ({}); the in-app list still has it", e.javaClass.simpleName)
                true
            }
            if (!alive) devices.forget(token)
        }
    }

    companion object {
        private val time = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.UK)

        /** The text people see. Kept short: lock screens cut long lines. */
        fun message(note: NotificationView): PushMessage {
            val who = note.actor?.name ?: "Someone"
            val trip = note.ride?.let { " from ${it.from.short()} to ${it.to.short()}" }.orEmpty()
            val (title, body) = when (note.kind) {
                NotificationKind.RIDE_REQUEST -> "New seat request" to "$who wants a seat$trip."
                NotificationKind.REQUEST_ACCEPTED -> "You're in!" to "$who accepted your request$trip" +
                    note.ride?.let { " on ${time.format(it.departure)}." }.orEmpty()
                NotificationKind.REQUEST_DECLINED -> "Request declined" to "$who couldn't take you$trip."
                NotificationKind.REQUEST_WITHDRAWN -> "Request canceled" to "$who canceled their request$trip."
                NotificationKind.RIDE_CANCELLED -> "Ride canceled" to "$who canceled the ride$trip."
                NotificationKind.RIDE_COMPLETED -> "How was your ride?" to "Rate your trip with $who."
            }
            val data = buildMap {
                put("kind", note.kind.name)
                put("notificationId", note.id.toString())
                note.ride?.let { put("rideId", it.id.toString()) }
                note.bookingId?.let { put("bookingId", it.toString()) }
            }
            return PushMessage(title, body, data)
        }

        private fun String.short() = substringBefore(',').trim()
    }
}
