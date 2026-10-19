package dev.liftonway.backend.auth

import dev.liftonway.backend.common.ApiException
import dev.liftonway.backend.common.PhoneNumbers
import dev.liftonway.backend.common.Tokens
import dev.liftonway.backend.users.Me
import dev.liftonway.backend.users.MeController
import dev.liftonway.backend.users.UserRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class CodeRequest(@field:NotBlank val countryCode: String, @field:NotBlank val number: String)

/** [name] is only needed the first time: with it an unknown number signs up, without it the app is told to ask. */
data class CodeVerify(
    @field:NotBlank val countryCode: String,
    @field:NotBlank val number: String,
    @field:NotBlank val code: String,
    @field:Size(max = 60) val name: String? = null,
)

data class Session(val token: String, val expiresAt: Instant, val created: Boolean, val user: Me)

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val otp: OtpService,
    private val users: UserRepository,
    private val tokens: Tokens,
    private val me: MeController,
) {
    @PostMapping("/otp")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun requestCode(@Valid @RequestBody body: CodeRequest): OtpSent = otp.send(PhoneNumbers.normalise(body.countryCode, body.number), OtpPurpose.SIGN_IN)

    @PostMapping("/verify")
    @Transactional(noRollbackFor = [ApiException::class])
    fun verify(@Valid @RequestBody body: CodeVerify): Session {
        val phone = PhoneNumbers.normalise(body.countryCode, body.number)
        val existing = users.findByPhone(phone)
        val name = body.name?.trim()?.takeIf { it.isNotEmpty() }
        // Checked before the code is spent, so the app can ask for a name and retry with the same code.
        if (existing == null && name == null) {
            throw ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NAME_REQUIRED", "New number: send a name to create the account.")
        }
        otp.verify(phone, body.code, OtpPurpose.SIGN_IN)
        val user = existing ?: users.create(name!!, phone)
        val issued = tokens.issue(user.id)
        return Session(issued.token, issued.expiresAt, created = existing == null, user = me.me(user.id))
    }
}
