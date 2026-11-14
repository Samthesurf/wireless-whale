package com.samuel.wirelesswhale.qr

import com.samuel.wirelesswhale.core.model.WifiCredentials
import com.samuel.wirelesswhale.core.model.WifiSecurity

/**
 * Exact offline parser and encoder for Wi-Fi QR payloads.
 *
 * Accepted shape: `WIFI:T:<type>;S:<ssid>;P:<password>;H:<hidden>;;`
 * where `P` is omitted for passwordless networks and `H:true` is present
 * only for hidden networks.
 *
 * Security rules:
 * - OPEN is `T:nopass`. A missing `T` field with no password is also
 *   accepted as OPEN for interoperable open codes such as
 *   `WIFI:S:OpenNet;;`. A missing `T` with a non-empty password is
 *   ambiguous and rejected rather than guessed.
 * - WPA and WPA2 map to WPA2_PERSONAL. WPA3 and SAE map to WPA3_PERSONAL.
 *   OWE maps to OWE. A WPA transitional token maps to WPA2_WPA3_PERSONAL
 *   on decode and is encoded back as `T:WPA`, which is the compatible
 *   floor, never a silent upgrade claim.
 * - WPA3_PERSONAL accepts 1..63 characters on both encode and decode.
 *   The 64-hex form is WPA2 PSK specific (Android API 30
 *   WifiConfiguration preSharedKey) with no SAE source, so 64 characters
 *   are rejected for WPA3 on both paths for consistency.
 * - WPA2 decode accepts any characters of valid length so real-world codes
 *   with non-ASCII passphrases still parse; encode additionally requires
 *   printable ASCII per the interoperable format and fails with an
 *   explicit charset message, never a misleading type message.
 * - WEP, EAP/enterprise variants, DPP, and unknown types are rejected with
 *   an explicit failure. Security is never silently downgraded.
 * - Enterprise auxiliary fields (E, PH, A, identity, and friends) cause a
 *   failure even when the type token looks personal, so enterprise config
 *   is never silently dropped.
 * - Unknown extension keys are ignored for forward compatibility, but any
 *   duplicated key (known or unknown) is ambiguous and rejected.
 *
 * Safety rules:
 * - All failure messages are constants. SSIDs, passwords, and raw payloads
 *   never appear in messages, exceptions, or logs.
 * - SSID and password contents are preserved exactly: no trimming and no
 *   case or Unicode normalization.
 * - Payloads over [MAX_PAYLOAD_CHARS] characters are rejected.
 */
object WifiQrCodec {

  const val MAX_PAYLOAD_CHARS = 4096
  const val MAX_SSID_UTF8_BYTES = 32
  const val MIN_WPA_PASSWORD_CHARS = 8
  const val MAX_WPA_PASSWORD_CHARS = 63
  const val MAX_PASSWORD_CHARS = 64

  private val ESCAPABLE = setOf('\\', ';', ',', ':', '"')

  private val ENTERPRISE_KEYS = setOf(
    "E", "EAP", "PH", "PH2", "A", "I", "ANON", "ANONYMOUS",
    "DOMAIN", "CERT", "KEYMGMT"
  )

  private val KEY_PATTERN = Regex("^[A-Z][A-Z0-9]*$")

  /**
   * Parses a raw QR text payload. Returns success with exact credentials,
   * or failure with a safe generic message that contains no secret material.
   */
  fun decode(payload: String): Result<WifiCredentials> {
    if (payload.length > MAX_PAYLOAD_CHARS) {
      return fail("Wi-Fi QR payload is too large.")
    }
    if (!payload.startsWith("WIFI:")) {
      return fail("Not a Wi-Fi QR code.")
    }
    if (!payload.endsWith(";;")) {
      return fail("Wi-Fi QR payload is malformed.")
    }
    val body = payload.substring(5, payload.length - 1)
    val segments = splitUnescaped(body, ';')
    if (segments.isEmpty() || segments.last().isNotEmpty()) {
      return fail("Wi-Fi QR payload is malformed.")
    }
    val fields = mutableMapOf<String, String>()
    for (segment in segments.dropLast(1)) {
      if (segment.isEmpty()) {
        return fail("Wi-Fi QR payload is malformed.")
      }
      val colon = indexOfUnescaped(segment, ':')
      if (colon < 0) {
        return fail("Wi-Fi QR payload is malformed.")
      }
      val key = segment.substring(0, colon)
      if (!KEY_PATTERN.matches(key)) {
        return fail("Wi-Fi QR payload is malformed.")
      }
      if (fields.containsKey(key)) {
        return fail("Wi-Fi QR payload is ambiguous.")
      }
      fields[key] = segment.substring(colon + 1)
    }

    if (fields.keys.any { it in ENTERPRISE_KEYS }) {
      return fail("Enterprise networks are not supported. Enter the details manually.")
    }

    val typeRaw = fields["T"]
    if (typeRaw == null) {
      // Interoperable open codes often omit T entirely (e.g.
      // WIFI:S:OpenNet;;). Accept only when no password is present;
      // a password without a type is ambiguous, never guessed.
      val openPasswordRaw = fields["P"] ?: ""
      val openPassword = try {
        unescape(openPasswordRaw)
      } catch (e: IllegalArgumentException) {
        return fail("Wi-Fi QR payload is malformed.")
      }
      if (openPassword.isNotEmpty()) {
        return fail("Wi-Fi QR payload is ambiguous.")
      }
      return decodeWithSecurity(
        fields = fields,
        security = WifiSecurity.OPEN,
        passwordOverride = ""
      )
    }
    val typeToken = try {
      unescape(typeRaw)
    } catch (e: IllegalArgumentException) {
      return fail("Wi-Fi QR payload is malformed.")
    }
    if (typeToken.isEmpty()) {
      return fail("Wi-Fi QR is missing the network type.")
    }
    val security = when (val upper = typeToken.uppercase()) {
      "NOPASS" -> WifiSecurity.OPEN
      "WPA", "WPA2" -> WifiSecurity.WPA2_PERSONAL
      "WPA3", "SAE" -> WifiSecurity.WPA3_PERSONAL
      "WPA2/WPA3", "WPA2-WPA3", "WPA2_WPA3" -> WifiSecurity.WPA2_WPA3_PERSONAL
      "OWE" -> WifiSecurity.OWE
      "WEP" -> return fail("WEP networks are not supported. Enter the details manually.")
      "DPP" -> return fail("DPP networks are not supported. Enter the details manually.")
      else -> {
        if (upper.contains("EAP") || upper == "ENTERPRISE" || upper.contains("802") || upper.contains("1X")) {
          return fail("Enterprise networks are not supported. Enter the details manually.")
        }
        return fail("Unsupported network type in QR code.")
      }
    }

    val ssidRaw = fields["S"] ?: return fail("Wi-Fi QR is missing the network name.")
    val ssid = try {
      unescape(ssidRaw)
    } catch (e: IllegalArgumentException) {
      return fail("Wi-Fi QR payload is malformed.")
    }
    return decodeWithSecurity(fields = fields, security = security, ssidOverride = ssid)
  }

  private fun decodeWithSecurity(
    fields: Map<String, String>,
    security: WifiSecurity,
    ssidOverride: String? = null,
    passwordOverride: String? = null
  ): Result<WifiCredentials> {
    val ssid = ssidOverride ?: try {
      unescape(fields["S"] ?: return fail("Wi-Fi QR is missing the network name."))
    } catch (e: IllegalArgumentException) {
      return fail("Wi-Fi QR payload is malformed.")
    }
    // Empty SSID is rejected; whitespace-only is preserved exactly and left
    // to the platform to accept or reject. Never trim.
    if (ssid.isEmpty()) {
      return fail("Wi-Fi QR is missing the network name.")
    }
    if (ssid.toByteArray(Charsets.UTF_8).size > MAX_SSID_UTF8_BYTES) {
      return fail("Network name in QR code is too long.")
    }

    val password = passwordOverride ?: try {
      unescape(fields["P"] ?: "")
    } catch (e: IllegalArgumentException) {
      return fail("Wi-Fi QR payload is malformed.")
    }
    when (security) {
      WifiSecurity.OPEN, WifiSecurity.OWE -> {
        if (password.isNotEmpty()) {
          return fail("Wi-Fi QR payload is contradictory.")
        }
      }
      WifiSecurity.WPA2_PERSONAL, WifiSecurity.WPA2_WPA3_PERSONAL -> {
        if (!isValidWpaPasswordLength(password)) {
          return fail("Password in QR code has an invalid length.")
        }
      }
      WifiSecurity.WPA3_PERSONAL -> {
        if (password.length !in 1..MAX_WPA_PASSWORD_CHARS) {
          return fail("Password in QR code has an invalid length.")
        }
      }
      WifiSecurity.WEP, WifiSecurity.ENTERPRISE, WifiSecurity.UNKNOWN -> {
        return fail("Unsupported network type in QR code.")
      }
    }

    val hiddenRaw = fields["H"]
    val hidden = if (hiddenRaw == null) {
      false
    } else {
      val hiddenToken = try {
        unescape(hiddenRaw)
      } catch (e: IllegalArgumentException) {
        return fail("Wi-Fi QR payload is malformed.")
      }
      when (hiddenToken.uppercase()) {
        "TRUE" -> true
        "FALSE" -> false
        else -> return fail("Wi-Fi QR payload is malformed.")
      }
    }

    return Result.success(WifiCredentials(ssid = ssid, password = password, security = security, hidden = hidden))
  }

  /**
   * Encodes credentials to the interoperable `WIFI:...` payload.
   * Throws IllegalArgumentException with a safe generic message for
   * unsupported types or invalid values. Never logs or echoes secrets.
   */
  fun encode(credentials: WifiCredentials): String {
    val ssid = credentials.ssid
    require(ssid.isNotEmpty()) { "Network name is empty." }
    require(ssid.toByteArray(Charsets.UTF_8).size <= MAX_SSID_UTF8_BYTES) {
      "Network name is too long."
    }
    val password = credentials.password
    val typeToken = when (credentials.security) {
      WifiSecurity.OPEN -> {
        require(password.isEmpty()) { "Open networks do not use a password." }
        "nopass"
      }
      WifiSecurity.OWE -> {
        require(password.isEmpty()) { "This network type does not use a password." }
        "OWE"
      }
      WifiSecurity.WPA2_PERSONAL, WifiSecurity.WPA2_WPA3_PERSONAL -> {
        require(isValidWpaPasswordLength(password)) { "Password length is invalid." }
        require(password.all { it.code in 0x20..0x7E }) {
          "Password uses characters that cannot be shared in a QR code."
        }
        "WPA"
      }
      WifiSecurity.WPA3_PERSONAL -> {
        require(password.length in 1..MAX_WPA_PASSWORD_CHARS) { "Password length is invalid." }
        "WPA3"
      }
      WifiSecurity.WEP, WifiSecurity.ENTERPRISE, WifiSecurity.UNKNOWN -> {
        throw IllegalArgumentException("This network type cannot be shared as a QR code.")
      }
    }
    val out = StringBuilder("WIFI:T:").append(typeToken).append(";S:").append(escape(ssid)).append(';')
    if (password.isNotEmpty()) {
      out.append("P:").append(escape(password)).append(';')
    }
    if (credentials.hidden) {
      out.append("H:true;")
    }
    out.append(';')
    return out.toString()
  }

  /**
   * Length check shared by encode and decode for WPA2: 8..63 characters of
   * any kind, plus the 64-hex pre-shared-key form. Decode additionally
   * accepts non-ASCII 8..63 passphrases for real-world interop; encode
   * separately requires printable ASCII and reports a charset-specific
   * error so a Unicode password is never misreported as an unknown type.
   */
  private fun isValidWpaPasswordLength(password: String): Boolean {
    if (password.length in MIN_WPA_PASSWORD_CHARS..MAX_WPA_PASSWORD_CHARS) return true
    return password.length == MAX_PASSWORD_CHARS &&
      password.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
  }

  private fun escape(raw: String): String = buildString(raw.length) {
    for (c in raw) {
      if (c in ESCAPABLE) append('\\')
      append(c)
    }
  }

  private fun unescape(raw: String): String {
    if (!raw.contains('\\')) return raw
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
      val c = raw[i]
      if (c == '\\') {
        if (i + 1 >= raw.length) throw IllegalArgumentException("bad escape")
        val next = raw[i + 1]
        if (next !in ESCAPABLE) throw IllegalArgumentException("bad escape")
        out.append(next)
        i += 2
      } else {
        out.append(c)
        i++
      }
    }
    return out.toString()
  }

  private fun splitUnescaped(value: String, delimiter: Char): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    while (i < value.length) {
      val c = value[i]
      if (c == '\\' && i + 1 < value.length) {
        current.append(c).append(value[i + 1])
        i += 2
        continue
      }
      if (c == delimiter) {
        parts.add(current.toString())
        current.clear()
        i++
        continue
      }
      current.append(c)
      i++
    }
    parts.add(current.toString())
    return parts
  }

  private fun indexOfUnescaped(value: String, target: Char): Int {
    var i = 0
    while (i < value.length) {
      val c = value[i]
      if (c == '\\') {
        i += 2
        continue
      }
      if (c == target) return i
      i++
    }
    return -1
  }

  private fun fail(message: String): Result<WifiCredentials> =
    Result.failure(IllegalArgumentException(message))
}
