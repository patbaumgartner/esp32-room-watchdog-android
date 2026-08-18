package com.patbaumgartner.roomwatchdog.data.gotify

import com.patbaumgartner.roomwatchdog.data.network.EndpointIssue
import com.patbaumgartner.roomwatchdog.data.network.EndpointValidationException
import com.patbaumgartner.roomwatchdog.data.network.gotifyBaseUrl
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class GotifyException(val kind: Kind, cause: Throwable? = null) :
    Exception(kind.name, cause) {

    enum class Kind { NotHttps, Auth, Unreachable, ApplicationToken, Unknown }
}

class GotifyClient(private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun currentUser(baseUrl: String, clientToken: String): Result<GotifyUser> =
        call(baseUrl, clientToken) { base ->
            val text = request(base, "current/user").clientToken(clientToken).build().readText()
            json.decodeFromString<GotifyUser>(text)
        }

    suspend fun messagesSince(
        baseUrl: String,
        clientToken: String,
        sinceId: Long,
        limit: Int = MAX_MESSAGES,
    ): Result<List<GotifyMessage>> = call(baseUrl, clientToken) { base ->
        val text = Request.Builder()
            .url(
                base.newBuilder().addPathSegment("message")
                    .addQueryParameter("limit", limit.coerceIn(1, MAX_MESSAGES).toString()).build(),
            )
            .clientToken(clientToken)
            .build()
            .readText()
        json.decodeFromString<PagedMessages>(text)
            .messages
            .filter { it.id > sinceId }
            .sortedBy { it.id }
    }

    private fun Request.Builder.clientToken(token: String) =
        header("Authorization", "Bearer $token")

    private fun request(baseUrl: HttpUrl, path: String) =
        Request.Builder().url(baseUrl.newBuilder().addPathSegment(path).build())

    private fun Request.readText(): String {
        http.newCall(this).execute().use { response ->
            if (!response.isSuccessful) {
                throw GotifyException(
                    if (response.code == 401 || response.code == 403) {
                        GotifyException.Kind.Auth
                    } else {
                        GotifyException.Kind.Unknown
                    },
                )
            }
            // The largest expected reply is a page of clipped messages; refuse to buffer more.
            return response.peekBody(MAX_BODY_BYTES).string()
        }
    }

    private suspend fun <T> call(
        baseUrl: String,
        clientToken: String? = null,
        block: (HttpUrl) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        if (clientToken != null && clientToken.startsWith(APPLICATION_TOKEN_PREFIX)) {
            return@withContext Result.failure(GotifyException(GotifyException.Kind.ApplicationToken))
        }
        runCatching { block(gotifyBaseUrl(baseUrl)) }.recoverCatching { error ->
            throw when (error) {
                is GotifyException -> error
                is EndpointValidationException -> GotifyException(
                    if (error.issue == EndpointIssue.Insecure) GotifyException.Kind.NotHttps
                    else GotifyException.Kind.Unreachable,
                    error,
                )

                is IOException -> GotifyException(GotifyException.Kind.Unreachable, error)
                else -> GotifyException(GotifyException.Kind.Unknown, error)
            }
        }
    }

    companion object {
        const val APPLICATION_TOKEN_PREFIX = "gtfya."
        private const val MAX_MESSAGES = 200
        private const val MAX_BODY_BYTES = 1L * 1024 * 1024
    }
}
