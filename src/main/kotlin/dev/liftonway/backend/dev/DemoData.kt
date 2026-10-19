package dev.liftonway.backend.dev

import dev.liftonway.backend.common.LiftOnWayProperties
import dev.liftonway.backend.places.PlaceRepository
import dev.liftonway.backend.rides.RideRepository
import dev.liftonway.backend.users.UserRepository
import dev.liftonway.backend.vehicles.Vehicle
import dev.liftonway.backend.vehicles.VehicleRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.math.absoluteValue

/**
 * Dev only (liftonway.demo-data): made-up drivers and a week of rides between the sample places, so search
 * returns something. Re-running tops up any days that have no demo rides yet. Phone numbers are placeholders
 * in the +92000000xxxx range, which no real subscriber has.
 */
@Component
@ConditionalOnBooleanProperty("liftonway.demo-data")
class DemoData(
    private val users: UserRepository,
    private val vehicles: VehicleRepository,
    private val rides: RideRepository,
    private val places: PlaceRepository,
    private val jdbc: JdbcClient,
    private val settings: LiftOnWayProperties,
    private val clock: Clock,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    private val drivers = listOf(
        Triple("Hamza Tariq", 4.8, "Toyota Corolla"),
        Triple("Ayesha Malik", 4.9, "Honda Civic"),
        Triple("Bilal Ahmed", 4.6, "Suzuki Cultus"),
        Triple("Sana Iqbal", 4.7, "Toyota Yaris"),
        Triple("Usman Raza", 4.5, "Honda City"),
        Triple("Mahnoor Khan", 4.8, "Kia Sportage"),
    )
    private val times = listOf(LocalTime.of(7, 0), LocalTime.of(8, 30), LocalTime.of(12, 15), LocalTime.of(17, 45), LocalTime.of(19, 30))

    @Transactional
    override fun run(args: ApplicationArguments) {
        val ids = drivers.mapIndexed { i, (name, rating, car) ->
            val phone = "+92000000%04d".format(i + 1)
            val user = users.findByPhone(phone) ?: users.create(name, phone, rating)
            if (vehicles.find(user.id) == null) vehicles.save(user.id, Vehicle(car, "2022", "DEMO ${100 + i}", "White", airConditioning = true, heater = false))
            user.id to car
        }
        val all = places.search("", limit = 100)
        val today = clock.instant().atZone(settings.zone).toLocalDate()
        var created = 0
        for (day in 0L..6L) {
            val date = today.plusDays(day)
            if (hasDemoRides(date, ids.map { it.first })) continue
            for (from in all) for (to in all) {
                if (from.id == to.id) continue
                // Mostly within a city, plus the Islamabad and Rawalpindi pairs and a few intercity runs.
                val twinCities = setOf("Islamabad", "Rawalpindi")
                val near = from.area == to.area || (from.area in twinCities && to.area in twinCities)
                val seed = (from.id + to.id + date).hashCode().absoluteValue
                if (!near && seed % 5 != 0) continue
                times.forEachIndexed { i, time ->
                    if ((seed + i) % 4 == 3) return@forEachIndexed
                    val departure = date.atTime(time).atZone(settings.zone).toInstant()
                    if (departure.isBefore(clock.instant())) return@forEachIndexed
                    val (driver, car) = ids[(seed + i) % ids.size]
                    val stops = all.filter { it.id != from.id && it.id != to.id && it.area in setOf(from.area, to.area) }
                    val points = listOf(from.id) + (if ((seed + i) % 2 == 0 && stops.isNotEmpty()) listOf(stops[(seed + i) % stops.size].id) else emptyList()) + to.id
                    val price = (if (near) 350 else 1800) + ((seed / 7 + i * 113) % 12) * 50
                    rides.create(UUID.randomUUID(), driver, departure, points, 2 + (seed + i * 3) % 3, price, "", car, clock.instant())
                    created++
                }
            }
        }
        if (created > 0) log.info("Demo data: added {} rides for the coming week", created)
    }

    private fun hasDemoRides(date: LocalDate, drivers: List<UUID>): Boolean = jdbc.sql(
        "select exists(select 1 from rides where driver_id in (:drivers) and departure >= :start and departure < :end)",
    )
        .param("drivers", drivers)
        .param("start", Timestamp.from(date.atStartOfDay(settings.zone).toInstant()))
        .param("end", Timestamp.from(date.plusDays(1).atStartOfDay(settings.zone).toInstant()))
        .query(Boolean::class.java).single()
}
