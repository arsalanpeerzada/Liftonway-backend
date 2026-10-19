package dev.liftonway.backend.auth

import dev.liftonway.backend.common.ApiException
import dev.liftonway.backend.common.Invalid
import dev.liftonway.backend.common.LiftOnWayProperties
import dev.liftonway.backend.common.PhoneNumbers
import dev.liftonway.backend.common.TooManyRequests
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.SecretKey

enum class OtpPurpose { SIGN_IN, CHANGE_PHONE }

/** Sends the code. Production plugs in an SMS provider; dev and test use a fixed code and send nothing. */
fun interface SmsSender {
    fun send(e164Phone: String, text: String)
}

data class OtpSent(val codeLength: Int, val expiresInSeconds: Long, val resendInSeconds: Long)

/**
 * One-time codes for sign-in and changing number. Only an HMAC of each code is stored, attempts per code are
 * capped, and each number can request a limited number of codes per hour. Codes are never logged.
 */
@Service
class OtpService(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    private val settings: LiftOnWayProperties,
    private val signingKey: SecretKey,
    sms: ObjectProvider<SmsSender>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val otp = settings.otp
    private val random = SecureRandom()
    private val sender: SmsSender? = sms.ifAvailable

    init {
        check(otp.devCode != null || sender != null) {
            "No SmsSender bean and no liftonway.otp.dev-code: sign-in can't send codes. Configure an SMS provider."
        }
    }

    @Transactional
    fun send(phone: String, purpose: OtpPurpose, userId: UUID? = null): OtpSent {
        val now = clock.instant()
        val recent = jdbc.sql("select created_at from otp_challenges where phone = :phone and created_at > :since order by created_at desc")
            .param("phone", phone).param("since", Timestamp.from(now.minus(Duration.ofHours(1))))
            .query { rs, _ -> rs.getTimestamp(1).toInstant() }.list()
        recent.firstOrNull()?.let { last ->
            val wait = Duration.between(now, last.plus(otp.resendAfter))
            if (!wait.isNegative && !wait.isZero) throw TooManyRequests("Wait a moment before asking for another code.", wait)
        }
        if (recent.size >= otp.maxPerHour) {
            throw TooManyRequests("Too many codes requested. Try again later.", Duration.between(now, recent.last().plus(Duration.ofHours(1))))
        }

        val code = otp.devCode ?: (0 until otp.length).joinToString("") { random.nextInt(10).toString() }
        val id = UUID.randomUUID()
        jdbc.sql(
            """insert into otp_challenges (id, phone, purpose, user_id, code_hash, created_at, expires_at)
               values (:id, :phone, :purpose, :user, :hash, :now, :expires)""",
        )
            .param("id", id).param("phone", phone).param("purpose", purpose.name).param("user", userId)
            .param("hash", hash(id, code)).param("now", Timestamp.from(now)).param("expires", Timestamp.from(now.plus(otp.ttl)))
            .update()
        sender?.takeIf { otp.devCode == null }?.send(phone, "Your Lift On Way code is $code. It expires in ${otp.ttl.toMinutes()} minutes.")
        log.info("Sent {} code to {}", purpose, PhoneNumbers.masked(phone))
        return OtpSent(otp.length, otp.ttl.toSeconds(), otp.resendAfter.toSeconds())
    }

    /** Consumes the latest live code for this number and purpose, or throws with a code the app can show. */
    @Transactional(noRollbackFor = [ApiException::class])
    fun verify(phone: String, code: String, purpose: OtpPurpose, userId: UUID? = null) {
        val now = clock.instant()
        val challenge = jdbc.sql(
            """select id, code_hash, attempts, expires_at from otp_challenges
               where phone = :phone and purpose = :purpose and consumed_at is null
                 and (cast(:user as uuid) is null or user_id = :user)
               order by created_at desc limit 1 for update""",
        )
            .param("phone", phone).param("purpose", purpose.name).param("user", userId)
            .query { rs, _ -> Challenge(rs.getObject("id", UUID::class.java), rs.getString("code_hash"), rs.getInt("attempts"), rs.getTimestamp("expires_at").toInstant()) }
            .optional().orElse(null)
            ?: throw Invalid("CODE_NOT_REQUESTED", "Ask for a new code.")

        if (challenge.expiresAt.isBefore(now)) throw Invalid("CODE_EXPIRED", "That code has expired. Ask for a new one.")
        if (challenge.attempts >= otp.maxAttempts) throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS", "Too many wrong codes. Ask for a new one.")

        val matches = MessageDigest.isEqual(hash(challenge.id, code.trim()).toByteArray(), challenge.codeHash.toByteArray())
        if (!matches) {
            jdbc.sql("update otp_challenges set attempts = attempts + 1 where id = :id").param("id", challenge.id).update()
            throw Invalid("INVALID_CODE", "That code didn't work.")
        }
        jdbc.sql("update otp_challenges set consumed_at = :now where id = :id").param("now", Timestamp.from(now)).param("id", challenge.id).update()
    }

    private data class Challenge(val id: UUID, val codeHash: String, val attempts: Int, val expiresAt: Instant)

    /** HMAC-SHA256 keyed with the server's signing key, salted by the challenge id. */
    private fun hash(id: UUID, code: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(signingKey) }
        return HexFormat.of().formatHex(mac.doFinal("$id:$code".toByteArray()))
    }
}
