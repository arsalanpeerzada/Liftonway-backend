package dev.liftonway.backend

import com.sun.net.httpserver.HttpServer
import dev.liftonway.backend.auth.TwilioProperties
import dev.liftonway.backend.auth.TwilioSmsSender
import dev.liftonway.backend.common.ApiException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Base64
import kotlin.test.assertEquals

/** The Twilio adapter against a local stand-in for Twilio's Messages API. No real messages are sent. */
class TwilioSmsTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var path = ""
    private var auth = ""
    private var form = emptyMap<String, String>()
    private var status = 201

    init {
        server.createContext("/") { exchange ->
            path = exchange.requestURI.path
            auth = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            form = exchange.requestBody.readAllBytes().decodeToString().split('&').associate {
                val (k, v) = it.split('=', limit = 2)
                URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
            }
            val reply = """{"sid":"SM00000000000000000000000000000000","status":"accepted"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun sender(from: String = "", service: String = "MG-test-service") = TwilioSmsSender(
        TwilioProperties(
            accountSid = "AC-test-account",
            apiKeySid = "SK-test-key",
            apiKeySecret = "test-secret",
            messagingServiceSid = service,
            from = from,
            baseUrl = "http://127.0.0.1:${server.address.port}",
        ),
    )

    @Test
    fun `sends through the messaging service with the API key`() {
        sender().send("+923001234567", "Your Lift On Way code is 0000.")
        assertEquals("/2010-04-01/Accounts/AC-test-account/Messages.json", path)
        assertEquals("Basic " + Base64.getEncoder().encodeToString("SK-test-key:test-secret".toByteArray()), auth)
        assertEquals(mapOf("To" to "+923001234567", "Body" to "Your Lift On Way code is 0000.", "MessagingServiceSid" to "MG-test-service"), form)
    }

    @Test
    fun `can send from a single number instead`() {
        sender(from = "+15550000000", service = "").send("+923001234567", "hi")
        assertEquals("+15550000000", form["From"])
    }

    @Test
    fun `a Twilio failure becomes a retryable error for the app`() {
        status = 400
        val error = assertThrows<ApiException> { sender().send("+923001234567", "hi") }
        assertEquals("SMS_UNAVAILABLE", error.code)
    }
}
