package dev.applesideload.core

/**
 * Takes anything credential shaped out of text on its way into the log.
 *
 * This app handles an Apple ID, a two factor code, a pairing record full of
 * private keys and a signing identity. A diagnostics report is only worth
 * offering if sharing it is safe, so the rules below are deliberately blunt:
 * losing a bit of detail to a false positive costs a second guess, while
 * leaking a pairing key costs the device.
 */
object Redaction {
    private val rules: List<Pair<Regex, String>> = listOf(
        // An Apple ID, and any other address.
        Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""") to "[apple-id]",
        // A named secret and whatever follows it.
        Regex(
            """((?:password|passwd|pwd|secret|token|key|cookie|anisette|otp|code|dsid|pet|spd)""" +
                """["']?\s*[=:]\s*["']?)[A-Za-z0-9._\-+/%]{4,}""",
            RegexOption.IGNORE_CASE
        ) to "$1[redacted]",
        // PEM and plist key material.
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----""")
            to "[private-key]",
        // A device serial or UDID: 40 hex characters, or the 24 character form
        // with a dash.
        Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{16}\b""") to "[udid]",
        Regex("""\b[0-9a-fA-F]{40}\b""") to "[udid]",
        // Whatever is left that is long and opaque enough to be a credential.
        Regex("""\b[A-Za-z0-9+/_\-]{48,}={0,2}\b""") to "[redacted]"
    )

    fun apply(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        for ((pattern, replacement) in rules) {
            out = pattern.replace(out, replacement)
        }
        return out
    }
}
