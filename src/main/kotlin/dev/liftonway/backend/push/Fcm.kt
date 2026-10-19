package dev.liftonway.backend.push

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * Firebase Cloud Messaging. [credentialsFile] is a service account key from the Firebase console, mounted as a
 * secret file on the server (never committed, never in an environment variable's value). The project comes from
 * the key unless [projectId] overrides it.
 */
@ConfigurationProperties("liftonway.push.fcm")
data class FcmProperties(
    val credentialsFile: String = "",
    val projectId: String = "",
    val sendUrl: String = "https://fcm.googleapis.com",
    /** Notifications older than this aren't worth delivering (a request after the ride has gone, say). */
    val ttl: Duration = Duration.ofHours(12),
)

/**
 * Sends with the FCM HTTP v1 API. Access tokens come from the service account by the OAuth 2.0 JWT-bearer grant
 * and are reused until shortly before they expire.
 */
class FcmPushSender(private val props: FcmProperties, private val clock: Clock) : PushSender {
    private val log = LoggerFactory.getLogger(javaClass)
    private val account: ServiceAccount = ServiceAccount.read(File(props.credentialsFile))
    private val project = props.projectId.ifBlank { account.projectId }
    private val http = RestClient.builder()
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(5))
                setReadTimeout(Duration.ofSeconds(10))
            },
        )
        .build()

    @Volatile private var access: Pair<String, Instant>? = null

    init {
        log.info("Push notifications go through FCM for project {}", project)
    }

    override fun send(deviceToken: String, message: PushMessage): Boolean {
        val body = mapOf(
            "message" to mapOf(
                // `token` is marked deprecated in favour of `fid` in the v1 API, but is still accepted.
                "token" to deviceToken,
                "data" to message.data + mapOf("title" to message.title, "body" to message.body),
                "android" to mapOf("priority" to "HIGH", "ttl" to "${props.ttl.toSeconds()}s"),
            ),
        )
        var status: HttpStatusCode? = null
        var reply = ""
        http.post()
            .uri("${props.sendUrl}/v1/projects/{project}/messages:send", project)
            .headers { it.setBearerAuth(accessToken()) }
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange { _, response ->
                status = response.statusCode
                reply = response.body.readAllBytes().decodeToString()
            }
        val code = status ?: return true
        if (code.is2xxSuccessful) return true
        // 404 UNREGISTERED: the app was uninstalled or the token rotated. Anything else is worth a log line only.
        if (code.value() == 404 || "UNREGISTERED" in reply) return false
        if (code.value() == 401) access = null
        log.warn("FCM answered {}", code.value())
        return true
    }

    private fun accessToken(): String {
        access?.let { (token, expires) -> if (clock.instant().isBefore(expires.minusSeconds(60))) return token }
        val now = clock.instant()
        val assertion = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(account.privateKeyId).build(),
            JWTClaimsSet.Builder()
                .issuer(account.clientEmail)
                .audience(account.tokenUri)
                .claim("scope", SCOPE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .build(),
        ).apply { sign(RSASSASigner(account.privateKey)) }.serialize()
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            add("assertion", assertion)
        }
        val reply = http.post().uri(account.tokenUri)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map::class.java) ?: error("Empty token response")
        val token = reply["access_token"] as String
        val lifetime = (reply["expires_in"] as? Number)?.toLong() ?: 3600
        access = token to now.plusSeconds(lifetime)
        return token
    }

    /** The fields of a Google service account key file that this needs. */
    class ServiceAccount(val projectId: String, val clientEmail: String, val privateKeyId: String?, val privateKey: RSAPrivateKey, val tokenUri: String) {
        companion object {
            fun read(file: File): ServiceAccount {
                require(file.isFile) { "liftonway.push.fcm.credentials-file doesn't point at a readable file" }
                val json = JsonMapper.builder().build().readTree(file)
                val pem = json.path("private_key").asString()
                val der = Base64.getMimeDecoder().decode(pem.replace(Regex("-----[A-Z ]+-----"), ""))
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateKey
                return ServiceAccount(
                    projectId = json.path("project_id").asString(),
                    clientEmail = json.path("client_email").asString(),
                    privateKeyId = json.path("private_key_id").asString(null),
                    privateKey = key,
                    tokenUri = json.path("token_uri").asString("https://oauth2.googleapis.com/token"),
                )
            }
        }
    }

    private companion object {
        const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
    }
}
