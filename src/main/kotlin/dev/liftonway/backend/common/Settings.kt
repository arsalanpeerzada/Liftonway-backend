package dev.liftonway.backend.common

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration
import java.time.ZoneId

@ConfigurationProperties("liftonway")
data class LiftOnWayProperties(
    val zone: ZoneId = ZoneId.of("Asia/Karachi"),
    val jwt: Jwt = Jwt(),
    val otp: Otp = Otp(),
    /** A booked ride can't be cancelled once departure is closer than this. */
    val cancellationCutoff: Duration = Duration.ofHours(4),
    /** Fills the database with sample drivers and rides for the coming week (dev only). */
    val demoData: Boolean = false,
    val retention: Retention = Retention(),
    val rateLimit: RateLimit = RateLimit(),
) {
    data class RateLimit(
        val enabled: Boolean = true,
        val perUserPerMinute: Int = 120,
        val perAddressPerMinute: Int = 60,
        val codesPerAddressPerHour: Int = 10,
    )

    data class Retention(
        /** A ride its driver hasn't marked complete is completed automatically this long after departure. */
        val completeRidesAfter: Duration = Duration.ofHours(6),
        /** Notifications are deleted after this long. */
        val notifications: Duration = Duration.ofDays(90),
        /** Sign-in code records (masked use history for abuse checks) are deleted after this long. */
        val otpRecords: Duration = Duration.ofDays(30),
    )

    /** Access tokens are short-lived; the app renews them with a refresh token that lasts [refreshTtl]. */
    data class Jwt(val secret: String = "", val ttl: Duration = Duration.ofMinutes(15), val refreshTtl: Duration = Duration.ofDays(60))

    data class Otp(
        val length: Int = 4,
        val ttl: Duration = Duration.ofMinutes(5),
        val maxAttempts: Int = 5,
        val resendAfter: Duration = Duration.ofSeconds(30),
        val maxPerHour: Int = 5,
        /** Dev and test only: every code is this value and no SMS is sent. */
        val devCode: String? = null,
    )
}

@Configuration
class TimeConfig {
    /** Injected everywhere "now" matters, so tests can move time. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
