package dev.liftonway.backend.common

import dev.liftonway.backend.auth.RefreshTokens
import dev.liftonway.backend.notifications.Notifications
import dev.liftonway.backend.rides.RideService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration

/**
 * Housekeeping on a timer. Switched off in tests (liftonway.jobs.enabled=false), which call the services directly
 * with a controlled clock instead. Every job is safe to run on several servers at once.
 */
@Component
@EnableScheduling
@ConditionalOnBooleanProperty("liftonway.jobs.enabled", matchIfMissing = true)
class Jobs(
    private val rides: RideService,
    private val notifications: Notifications,
    private val refreshTokens: RefreshTokens,
    private val jdbc: JdbcClient,
    private val clock: Clock,
    private val settings: LiftOnWayProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Drivers usually tap "Complete"; this catches the rides they forget. */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    fun completeOverdueRides() {
        while (rides.completeOverdue(settings.retention.completeRidesAfter) > 0) Unit
    }

    /** Nightly retention: old notifications, expired sessions and spent sign-in codes. */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Karachi")
    @Transactional
    fun purgeOldRecords() {
        val retention = settings.retention
        val notes = notifications.purgeOlderThan(retention.notifications)
        val sessions = refreshTokens.purgeExpired(Duration.ofDays(7))
        val codes = jdbc.sql("delete from otp_challenges where created_at < :before")
            .param("before", Timestamp.from(clock.instant().minus(retention.otpRecords))).update()
        log.info("Retention: removed {} notifications, {} expired sessions, {} code records", notes, sessions, codes)
    }
}
