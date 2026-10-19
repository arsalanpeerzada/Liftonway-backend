package dev.liftonway.backend.common

/** Phone numbers as the app sends them (country code + local number) turned into one E.164 form. */
object PhoneNumbers {
    private val countryCode = Regex("""^\+[1-9]\d{0,3}$""")

    /** "+92" and "0300 1234567" (or "3001234567") become "+923001234567". */
    fun normalise(code: String, number: String): String {
        val cc = code.trim()
        if (!countryCode.matches(cc)) throw Invalid("INVALID_PHONE", "Country code must look like +92.")
        val local = number.filter(Char::isDigit).trimStart('0')
        val full = cc + local
        if (local.length < 7 || full.length > 16) throw Invalid("INVALID_PHONE", "Enter a full phone number.")
        return full
    }

    /** "+92300*****67", for the few places a number appears in logs. */
    fun masked(e164: String): String = if (e164.length <= 8) "***" else e164.take(6) + "*".repeat(e164.length - 8) + e164.takeLast(2)
}
