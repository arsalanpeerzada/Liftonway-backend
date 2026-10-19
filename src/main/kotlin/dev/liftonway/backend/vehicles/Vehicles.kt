package dev.liftonway.backend.vehicles

import dev.liftonway.backend.common.NotFound
import dev.liftonway.backend.common.userId
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

data class Vehicle(
    @field:NotBlank @field:Size(max = 60) val name: String,
    @field:NotBlank @field:Size(max = 20) val model: String,
    @field:NotBlank @field:Size(max = 20) val plate: String,
    @field:NotBlank @field:Size(max = 30) val colour: String,
    val airConditioning: Boolean,
    val heater: Boolean,
)

@Repository
class VehicleRepository(private val jdbc: JdbcClient, private val clock: Clock) {
    fun find(userId: UUID): Vehicle? = jdbc.sql("select * from vehicles where user_id = :id").param("id", userId)
        .query { rs, _ ->
            Vehicle(rs.getString("name"), rs.getString("model"), rs.getString("plate"), rs.getString("colour"), rs.getBoolean("air_conditioning"), rs.getBoolean("heater"))
        }
        .optional().orElse(null)

    fun save(userId: UUID, v: Vehicle) {
        jdbc.sql(
            """insert into vehicles (user_id, name, model, plate, colour, air_conditioning, heater, updated_at)
               values (:id, :name, :model, :plate, :colour, :ac, :heater, :now)
               on conflict (user_id) do update set name = excluded.name, model = excluded.model, plate = excluded.plate,
                   colour = excluded.colour, air_conditioning = excluded.air_conditioning, heater = excluded.heater, updated_at = excluded.updated_at""",
        )
            .param("id", userId).param("name", v.name.trim()).param("model", v.model.trim()).param("plate", v.plate.trim().uppercase())
            .param("colour", v.colour.trim()).param("ac", v.airConditioning).param("heater", v.heater).param("now", Timestamp.from(clock.instant()))
            .update()
    }
}

@RestController
@RequestMapping("/api/v1/me/vehicle")
class VehicleController(private val vehicles: VehicleRepository) {
    @GetMapping
    fun get(@AuthenticationPrincipal jwt: Jwt): Vehicle = vehicles.find(jwt.userId) ?: throw NotFound("Vehicle")

    @PutMapping
    fun put(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody body: Vehicle): Vehicle {
        vehicles.save(jwt.userId, body)
        return vehicles.find(jwt.userId)!!
    }
}
