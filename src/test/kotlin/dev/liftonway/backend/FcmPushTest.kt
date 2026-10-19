package dev.liftonway.backend

import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import dev.liftonway.backend.notifications.Actor
import dev.liftonway.backend.notifications.NotificationKind
import dev.liftonway.backend.notifications.NotificationView
import dev.liftonway.backend.notifications.RideBrief
import dev.liftonway.backend.push.FcmProperties
import dev.liftonway.backend.push.FcmPushSender
import dev.liftonway.backend.push.PushMessage
import dev.liftonway.backend.push.PushNotifier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The FCM sender against local stand-ins for Google's token and send endpoints, with a throwaway key. */
class FcmPushTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val tokenCalls = AtomicInteger()
    private var assertion = ""
    private var sendPath = ""
    private var sendAuth = ""
    private var sendBody: Map<*, *> = emptyMap<String, Any>()
    private var sendStatus = 200

    init {
        server.createContext("/token") { exchange ->
            tokenCalls.incrementAndGet()
            val form = exchange.requestBody.readAllBytes().decodeToString().split('&').associate {
                val (k, v) = it.split('=', limit = 2)
                k to URLDecoder.decode(v, Charsets.UTF_8)
            }
            assertion = form["assertion"].orEmpty()
            reply(exchange, 200, """{"access_token":"access-1","expires_in":3600,"token_type":"Bearer"}""")
        }
        server.createContext("/v1/") { exchange ->
            sendPath = exchange.requestURI.path
            sendAuth = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            sendBody = JsonMapper.builder().build().readValue(exchange.requestBody.readAllBytes(), Map::class.java)
            reply(exchange, sendStatus, if (sendStatus == 404) """{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}""" else """{"name":"m"}""")
        }
        server.start()
    }

    private fun reply(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun sender(dir: File): FcmPushSender {
        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(keys.private.encoded) + "\n-----END PRIVATE KEY-----\n"
        val file = File(dir, "test-account.json")
        file.writeText(
            JsonMapper.builder().build().writeValueAsString(
                mapOf(
                    "type" to "service_account",
                    "project_id" to "test-project",
                    "private_key_id" to "kid-1",
                    "private_key" to pem,
                    "client_email" to "push@test-project.iam.example",
                    "token_uri" to "$base/token",
                ),
            ),
        )
        return FcmPushSender(FcmProperties(credentialsFile = file.path, sendUrl = base), MutableClock(Instant.parse("2026-10-19T05:00:00Z")))
    }

    private val message = PushMessage("New seat request", "Asma wants a seat.", mapOf("kind" to "RIDE_REQUEST"))

    @Test
    fun `signs a token request with the service account, then sends a high-priority data message`(@TempDir dir: File) {
        val fcm = sender(dir)
        assertTrue(fcm.send("device-1", message))
        assertTrue(fcm.send("device-2", message))

        assertEquals(1, tokenCalls.get(), "the access token is reused")
        val jwt = SignedJWT.parse(assertion)
        assertTrue(jwt.verify(RSASSAVerifier(keys.public as RSAPublicKey)))
        assertEquals("push@test-project.iam.example", jwt.jwtClaimsSet.issuer)
        assertEquals("https://www.googleapis.com/auth/firebase.messaging", jwt.jwtClaimsSet.getStringClaim("scope"))

        assertEquals("/v1/projects/test-project/messages:send", sendPath)
        assertEquals("Bearer access-1", sendAuth)
        val sent = sendBody["message"] as Map<*, *>
        assertEquals("device-2", sent["token"])
        assertEquals(mapOf("kind" to "RIDE_REQUEST", "title" to "New seat request", "body" to "Asma wants a seat."), sent["data"])
        assertEquals(mapOf("priority" to "HIGH", "ttl" to "43200s"), sent["android"])
    }

    @Test
    fun `an unregistered device is reported dead`(@TempDir dir: File) {
        sendStatus = 404
        assertFalse(sender(dir).send("gone", message))
    }

    @Test
    fun `push text is short and names the trip`() {
        val note = NotificationView(
            id = UUID.randomUUID(),
            kind = NotificationKind.REQUEST_ACCEPTED,
            actor = Actor(UUID.randomUUID(), "Hamza Tariq", null),
            ride = RideBrief(UUID.randomUUID(), "Blue Area, Jinnah Avenue", "Islamabad International Airport", OffsetDateTime.of(2026, 10, 20, 8, 0, 0, 0, ZoneOffset.ofHours(5))),
            bookingId = UUID.randomUUID(),
            seats = 1,
            bookingStatus = "CONFIRMED",
            actionable = false,
            read = false,
            createdAt = OffsetDateTime.now(),
        )
        val push = PushNotifier.message(note)
        assertEquals("You're in!", push.title)
        assertEquals("Hamza Tariq accepted your request from Blue Area to Islamabad International Airport on Tue 20 Oct, 8:00 am.", push.body)
        assertEquals(note.bookingId.toString(), push.data["bookingId"])
    }
}
