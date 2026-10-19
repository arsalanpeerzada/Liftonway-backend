package dev.liftonway.backend.users

import dev.liftonway.backend.auth.OtpPurpose
import dev.liftonway.backend.auth.OtpService
import dev.liftonway.backend.common.Conflict
import dev.liftonway.backend.common.NotFound
import dev.liftonway.backend.common.PhoneNumbers
import dev.liftonway.backend.common.userId
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

data class User(val id: UUID, val name: String, val phone: String, val photoUrl: String?, val rating: Double?)

@Repository
class UserRepository(private val jdbc: JdbcClient, private val clock: Clock) {
    private fun map(rs: java.sql.ResultSet) = User(
        id = rs.getObject("id", UUID::class.java),
        name = rs.getString("name"),
        phone = rs.getString("phone"),
        photoUrl = rs.getString("photo_url"),
        rating = rs.getObject("rating", BigDecimal::class.java)?.toDouble(),
    )

    fun find(id: UUID): User? = jdbc.sql("select * from users where id = :id").param("id", id).query { rs, _ -> map(rs) }.optional().orElse(null)

    fun findByPhone(phone: String): User? =
        jdbc.sql("select * from users where phone = :phone").param("phone", phone).query { rs, _ -> map(rs) }.optional().orElse(null)

    fun create(name: String, phone: String, rating: Double? = null): User {
        val id = UUID.randomUUID()
        jdbc.sql("insert into users (id, name, phone, rating, created_at) values (:id, :name, :phone, :rating, :now)")
            .param("id", id).param("name", name).param("phone", phone).param("rating", rating?.toBigDecimal())
            .param("now", java.sql.Timestamp.from(clock.instant()))
            .update()
        return User(id, name, phone, null, rating)
    }

    fun rename(id: UUID, name: String) {
        jdbc.sql("update users set name = :name where id = :id").param("name", name).param("id", id).update()
    }

    fun changePhone(id: UUID, phone: String) {
        jdbc.sql("update users set phone = :phone where id = :id").param("phone", phone).param("id", id).update()
    }
}

data class Me(val id: UUID, val name: String, val phone: String, val photoUrl: String?, val rating: Double?, val hasVehicle: Boolean)

data class UpdateMe(@field:NotBlank @field:Size(max = 60) val name: String)

data class PhoneRequest(@field:NotBlank val countryCode: String, @field:NotBlank val number: String)

data class PhoneVerify(@field:NotBlank val countryCode: String, @field:NotBlank val number: String, @field:NotBlank val code: String)

@RestController
@RequestMapping("/api/v1/me")
class MeController(private val users: UserRepository, private val otp: OtpService, private val jdbc: JdbcClient) {

    fun me(id: UUID): Me {
        val user = users.find(id) ?: throw NotFound("Account")
        val hasVehicle = jdbc.sql("select exists(select 1 from vehicles where user_id = :id)").param("id", id).query(Boolean::class.java).single()
        return Me(user.id, user.name, user.phone, user.photoUrl, user.rating, hasVehicle)
    }

    @GetMapping
    fun get(@AuthenticationPrincipal jwt: Jwt): Me = me(jwt.userId)

    @PatchMapping
    fun update(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody body: UpdateMe): Me {
        users.rename(jwt.userId, body.name.trim())
        return me(jwt.userId)
    }

    /** Step one of changing number: a code goes to the new number. */
    @PostMapping("/phone/otp")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun requestPhoneChange(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody body: PhoneRequest) =
        otp.send(PhoneNumbers.normalise(body.countryCode, body.number), OtpPurpose.CHANGE_PHONE, jwt.userId)

    @PostMapping("/phone/verify")
    @Transactional
    fun verifyPhoneChange(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody body: PhoneVerify): Me {
        val phone = PhoneNumbers.normalise(body.countryCode, body.number)
        otp.verify(phone, body.code, OtpPurpose.CHANGE_PHONE, jwt.userId)
        if (users.findByPhone(phone)?.let { it.id != jwt.userId } == true) throw Conflict("PHONE_TAKEN", "That number belongs to another account.")
        users.changePhone(jwt.userId, phone)
        return me(jwt.userId)
    }
}
