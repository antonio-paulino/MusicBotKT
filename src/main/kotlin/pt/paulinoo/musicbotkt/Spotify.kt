package pt.paulinoo.musicbotkt

import se.michaelthelin.spotify.SpotifyApi
import se.michaelthelin.spotify.model_objects.specification.Track

fun getSpotifyAccessToken(clientID: String, clientSecret: String): String {

    val spotifyApi = SpotifyApi.Builder()
        .setClientId(clientID)
        .setClientSecret(clientSecret)
        .build()

    val clientCredentialsRequest = spotifyApi.clientCredentials().build()

    return try {
        val credentials = clientCredentialsRequest.execute()
        credentials.accessToken
    } catch (e: Exception) {
        throw RuntimeException("Error fetching Spotify access token: ${e.message}")
    }
}

fun getSpotifyTrackInfo(accessTokenSpotify: String, spotifyLink: String): Track {
    val trackId = extractTrackIdFromLink(spotifyLink)

    val spotifyApi = SpotifyApi.Builder()
        .setAccessToken(accessTokenSpotify)
        .build()

    return spotifyApi.getTrack(trackId).build().execute()
}

fun extractTrackIdFromLink(spotifyLink: String): String {
    val regex = "track/([a-zA-Z0-9]+)".toRegex()
    val matchResult = regex.find(spotifyLink)
    return matchResult?.groups?.get(1)?.value ?: throw IllegalArgumentException("Invalid Spotify link")
}