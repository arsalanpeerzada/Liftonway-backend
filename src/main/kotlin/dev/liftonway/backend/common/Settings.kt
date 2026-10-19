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
) {
    data class Jwt(val secret: String = "", val ttl: Duration = Duration.ofDays(7))

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
