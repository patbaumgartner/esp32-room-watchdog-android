package com.patbaumgartner.roomwatchdog.data.gotify

import com.patbaumgartner.roomwatchdog.data.network.EndpointIssue
import com.patbaumgartner.roomwatchdog.data.network.EndpointValidationException
import com.patbaumgartner.roomwatchdog.data.network.gotifyBaseUrl
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GotifyException(val kind: Kind, cause: Throwable? = null) :
    Exception(kind.name, cause) {

    enum class Kind { NotHttps, Auth, Unreachable, ApplicationToken, Unknown }
}

class GotifyClient(private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun probe(baseUrl: String): Result<Unit> = call<Unit>(baseUrl) { base ->
        request(base, "version").build().readText()
    }

    /** Exchanges username/password for a long-lived client token; the password is never stored. */
    suspend fun createClientToken(
        baseUrl: String,
        username: String,
        password: String,
        clientName: String,
    ): Result<String> = call(baseUrl) { base ->
        val body = json.encodeToString(mapOf("name" to clientName))
            .toRequestBody("application/json".toMediaType())
        val text = request(base, "client")
            .header("Authorization", Credentials.basic(username, password))
            .post(body)
            .build()
            .readText()
        json.decodeFromString<CreatedClient>(text).token
    }

    suspend fun currentUser(baseUrl: String, clientToken: String): Result<GotifyUser> =
        call(baseUrl, clientToken) { base ->
            val text = request(base, "current/user").clientToken(clientToken).build().readText()
            json.decodeFromString<GotifyUser>(text)
        }

    suspend fun applications(baseUrl: String, clientToken: String): Result<List<GotifyApplication>> =
        call(baseUrl, clientToken) { base ->
            val text = request(base, "application").clientToken(clientToken).build().readText()
            json.decodeFromString<List<GotifyApplication>>(text)
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
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw GotifyException(
                    if (response.code == 401 || response.code == 403) {
                        GotifyException.Kind.Auth
                    } else {
                        GotifyException.Kind.Unknown
                    },
                )
            }
            return body
        }
    }

    private suspend fun <T> call(
        baseUrl: String,
        clientToken: String? = null,
        block: (HttpUrl) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        if (clientToken != null && clientToken.startsWith(APPLICATION_TOKEN_PREFIX)) {
                return@withContext Result.failure(
                    GotifyException(GotifyException.Kind.ApplicationToken),
                )
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
    }
}
