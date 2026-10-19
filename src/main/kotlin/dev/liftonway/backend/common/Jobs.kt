package dev.liftonway.backend.common

import dev.liftonway.backend.notifications.Notifications
import dev.liftonway.backend.rides.RideService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

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
    private val settings: LiftOnWayProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Drivers usually tap "Complete"; this catches the rides they forget. */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    fun completeOverdueRides() {
        while (rides.completeOverdue(settings.retention.completeRidesAfter) > 0) Unit
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Karachi")
    @Transactional
    fun purgeOldNotifications() {
        val gone = notifications.purgeOlderThan(settings.retention.notifications)
        if (gone > 0) log.info("Removed {} notifications older than {}", gone, settings.retention.notifications)
    }
}
