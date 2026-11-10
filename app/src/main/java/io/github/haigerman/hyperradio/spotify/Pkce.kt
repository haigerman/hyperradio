package io.github.haigerman.hyperradio.spotify

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Authorization Code Flow mit PKCE (RFC 7636) – der sichere Login-Weg für Apps ohne Server. */
object Pkce {
    private val random = SecureRandom()

    fun newVerifier(): String = randomUrlSafe(64)

    fun newState(): String = randomUrlSafe(16)

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun authorizeUrl(clientId: String, redirectUri: String, scopes: List<String>, verifier: String, state: String): String {
        val params = linkedMapOf(
            "client_id" to clientId,
            "response_type" to "code",
            "redirect_uri" to redirectUri,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge(verifier),
            "scope" to scopes.joinToString(" "),
            "state" to state,
        )
        return "https://accounts.spotify.com/authorize?" + formEncode(params)
    }

    fun formEncode(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}
