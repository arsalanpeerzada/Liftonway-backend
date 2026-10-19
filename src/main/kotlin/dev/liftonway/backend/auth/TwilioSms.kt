package dev.liftonway.backend.auth

import dev.liftonway.backend.common.ApiException
import dev.liftonway.backend.common.PhoneNumbers
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.time.Duration

/**
 * Twilio credentials, all from the environment (never committed). Use an API key and secret rather than the
 * account's auth token, so the key can be rotated or revoked on its own. Send either from a Messaging Service
 * (recommended: it handles sender selection and country rules) or from a single number.
 */
@ConfigurationProperties("liftonway.sms.twilio")
data class TwilioProperties(
    val accountSid: String = "",
    val apiKeySid: String = "",
    val apiKeySecret: String = "",
    val messagingServiceSid: String = "",
    val from: String = "",
    val baseUrl: String = "https://api.twilio.com",
)

/**
 * Sends codes through Twilio's Messages API. Active when `liftonway.sms.twilio.account-sid` is set.
 * The message text holds the code, so it is never logged; only the masked number and Twilio's message id are.
 */
@Component
@ConditionalOnProperty("liftonway.sms.twilio.account-sid")
class TwilioSmsSender(private val props: TwilioProperties) : SmsSender {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(props.apiKeySid.isNotBlank() && props.apiKeySecret.isNotBlank()) {
            "Twilio needs liftonway.sms.twilio.api-key-sid and api-key-secret."
        }
        require(props.messagingServiceSid.isNotBlank() || props.from.isNotBlank()) {
            "Twilio needs liftonway.sms.twilio.messaging-service-sid or from."
        }
    }

    private val client = RestClient.builder()
        .baseUrl(props.baseUrl)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(5))
                setReadTimeout(Duration.ofSeconds(10))
            },
        )
        .defaultHeaders { it.setBasicAuth(props.apiKeySid, props.apiKeySecret) }
        .build()

    override fun send(e164Phone: String, text: String) {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("To", e164Phone)
            add("Body", text)
            if (props.messagingServiceSid.isNotBlank()) add("MessagingServiceSid", props.messagingServiceSid) else add("From", props.from)
        }
        val sent = try {
            client.post()
                .uri("/2010-04-01/Accounts/{account}/Messages.json", props.accountSid)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map::class.java)
        } catch (e: RestClientException) {
            // Twilio's error text can echo the request; keep only the type and status.
            log.warn("Twilio rejected a code to {}: {}", PhoneNumbers.masked(e164Phone), e.javaClass.simpleName)
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SMS_UNAVAILABLE", "We couldn't send the code just now. Please try again shortly.")
        }
        log.info("Twilio accepted code to {} as {} ({})", PhoneNumbers.masked(e164Phone), sent?.get("sid"), sent?.get("status"))
    }
}
