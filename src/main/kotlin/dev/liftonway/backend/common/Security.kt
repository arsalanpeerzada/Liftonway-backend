package dev.liftonway.backend.common

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless API: the app sends `Authorization: Bearer <token>`, a JWT signed with HS256.
 * Sign-in, place search and the API docs are open; everything else needs a token.
 */
@Configuration
class SecurityConfig(private val settings: LiftOnWayProperties, private val env: Environment) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun signingKey(): SecretKey {
        val configured = settings.jwt.secret.toByteArray()
        if (configured.size >= 32) return SecretKeySpec(configured, "HmacSHA256")
        check(env.matchesProfiles("dev | test")) {
            "liftonway.jwt.secret (LIFTONWAY_JWT_SECRET) must be at least 32 bytes outside dev and test."
        }
        // Dev and test only: a fresh random key per run, so tokens don't survive a restart. Never logged.
        log.warn("No JWT secret configured; using a random key for this run (dev/test only).")
        return SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "HmacSHA256")
    }

    @Bean
    fun jwtEncoder(key: SecretKey): JwtEncoder = NimbusJwtEncoder(ImmutableSecret<SecurityContext>(key))

    /** Expiry is checked against the app's [Clock], the same one that issued the token. */
    @Bean
    fun jwtDecoder(key: SecretKey, clock: Clock): JwtDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build().apply {
        setJwtValidator(JwtTimestampValidator().apply { setClock(clock) })
    }

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain = http
        .csrf { it.disable() } // Bearer tokens only, no cookies, so CSRF doesn't apply.
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests {
            it.requestMatchers(HttpMethod.POST, "/api/v1/auth/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/places", "/actuator/health").permitAll()
                .requestMatchers("/docs/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .anyRequest().authenticated()
        }
        .oauth2ResourceServer { it.jwt { } }
        .build()
}

@Component
class Tokens(private val encoder: JwtEncoder, private val settings: LiftOnWayProperties, private val clock: Clock) {
    data class Issued(val token: String, val expiresAt: Instant)

    fun issue(userId: UUID): Issued {
        val now = clock.instant()
        val expires = now.plus(settings.jwt.ttl)
        val claims = JwtClaimsSet.builder().subject(userId.toString()).issuedAt(now).expiresAt(expires).build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return Issued(encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue, expires)
    }
}

/** The signed-in user's id, from the token's subject. */
val Jwt.userId: UUID get() = UUID.fromString(subject)
