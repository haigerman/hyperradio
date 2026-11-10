package io.github.haigerman.hyperradio.spotify

/**
 * Zugangsdaten der Spotify-App aus dem Spotify-Entwicklerportal (developer.spotify.com).
 * Die Client ID ist nicht geheim – mit PKCE braucht eine Handy-App kein Client Secret.
 * Einrichtung: siehe README, Abschnitt "Spotify".
 */
object SpotifyConfig {
    /** Hier die Client ID der eigenen Spotify-App eintragen. */
    const val CLIENT_ID = ""

    /** Muss im Spotify-Entwicklerportal genau so als Redirect URI eingetragen sein. */
    const val REDIRECT_URI = "hyperradio://spotify-callback"

    val SCOPES = listOf(
        "user-read-playback-state",
        "user-modify-playback-state",
        "user-read-currently-playing",
        "user-follow-read",
        "user-top-read",
    )

    val isConfigured: Boolean get() = CLIENT_ID.isNotBlank()
}
