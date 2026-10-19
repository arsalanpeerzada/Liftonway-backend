package dev.liftonway.backend.auth

import dev.liftonway.backend.common.ApiException
import dev.liftonway.backend.common.LiftOnWayProperties
import org.slf4j.LoggerFactory
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
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.SecretKey

class InvalidRefresh : ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH", "Your session has ended. Please sign in again.")

/**
 * Rotating refresh tokens. A token looks like `<id>.<secret>`; only an HMAC of the secret is stored. Each one
 * can be used once: refreshing spends it and returns the next. A spent or revoked token coming back means
 * someone copied it, so the whole sign-in (family) is ended.
 */
@Service
class RefreshTokens(
    private val jdbc: JdbcClient,
    private val signingKey: SecretKey,
    private val settings: LiftOnWayProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    data class Issued(val token: String, val expiresAt: Instant, val userId: UUID)

    private data class Row(val id: UUID, val userId: UUID, val familyId: UUID, val hash: String, val expiresAt: Instant, val usedAt: Instant?, val revokedAt: Instant?)

    /** Starts a new sign-in. */
    fun start(userId: UUID): Issued = issue(userId, UUID.randomUUID())

    /** Spends [presented] and returns its successor, or throws [InvalidRefresh]. */
    @Transactional(noRollbackFor = [InvalidRefresh::class])
    fun rotate(presented: String): Issued {
        val row = lock(presented) ?: throw InvalidRefresh()
        val now = clock.instant()
        if (row.usedAt != null || row.revokedAt != null) {
            revokeFamily(row.familyId, now)
            log.warn("Refresh token reused; ended that sign-in for user {}", row.userId)
            throw InvalidRefresh()
        }
        if (!row.expiresAt.isAfter(now)) throw InvalidRefresh()
        jdbc.sql("update refresh_tokens set used_at = :now where id = :id").param("now", Timestamp.from(now)).param("id", row.id).update()
        return issue(row.userId, row.familyId)
    }

    /** Signs out the device that holds [presented]. Unknown tokens are ignored, so sign-out always succeeds. */
    @Transactional
    fun revoke(presented: String) {
        lock(presented)?.let { revokeFamily(it.familyId, clock.instant()) }
    }

    /** Removes tokens that expired more than [after] ago. */
    fun purgeExpired(after: Duration): Int =
        jdbc.sql("delete from refresh_tokens where expires_at < :before").param("before", Timestamp.from(clock.instant().minus(after))).update()

    private fun issue(userId: UUID, family: UUID): Issued {
        val id = UUID.randomUUID()
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val now = clock.instant()
        val expires = now.plus(settings.jwt.refreshTtl)
        jdbc.sql(
            """insert into refresh_tokens (id, user_id, family_id, token_hash, created_at, expires_at)
               values (:id, :user, :family, :hash, :now, :expires)""",
        )
            .param("id", id).param("user", userId).param("family", family).param("hash", hash(id, secret))
            .param("now", Timestamp.from(now)).param("expires", Timestamp.from(expires))
            .update()
        return Issued("$id.$secret", expires, userId)
    }

    private fun lock(presented: String): Row? {
        val id = runCatching { UUID.fromString(presented.substringBefore('.')) }.getOrNull() ?: return null
        val secret = presented.substringAfter('.', "")
        if (secret.isEmpty()) return null
        val row = jdbc.sql("select * from refresh_tokens where id = :id for update").param("id", id)
            .query { rs, _ ->
                Row(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("user_id", UUID::class.java),
                    rs.getObject("family_id", UUID::class.java),
                    rs.getString("token_hash"),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getTimestamp("used_at")?.toInstant(),
                    rs.getTimestamp("revoked_at")?.toInstant(),
                )
            }
            .optional().orElse(null) ?: return null
        return row.takeIf { MessageDigest.isEqual(hash(id, secret).toByteArray(), it.hash.toByteArray()) }
    }

    private fun revokeFamily(family: UUID, now: Instant) {
        jdbc.sql("update refresh_tokens set revoked_at = :now where family_id = :family and revoked_at is null")
            .param("now", Timestamp.from(now)).param("family", family).update()
    }

    private fun hash(id: UUID, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(signingKey) }
        return HexFormat.of().formatHex(mac.doFinal("refresh:$id:$secret".toByteArray()))
    }
}
