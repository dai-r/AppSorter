package dev.dair.appsorter.categorizer

import aws.sdk.kotlin.services.cognitoidentity.CognitoIdentityClient
import aws.sdk.kotlin.services.cognitoidentity.model.GetCredentialsForIdentityRequest
import aws.sdk.kotlin.services.cognitoidentity.model.GetIdRequest
import dev.dair.appsorter.model.CategorizedApp
import dev.dair.appsorter.model.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class BedrockAppCategorizer(
    private val apiUrl: String,
    private val region: String,
    private val identityPoolId: String,
) : AppCategorizer {

    class RateLimitException(val scope: String) : RuntimeException("rate limit exceeded: $scope")
    class KillSwitchException : RuntimeException("service temporarily disabled")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val signer = SigV4Signer(region = region, service = "execute-api")

    private val credentialMutex = Mutex()
    private var cachedCredentials: CachedCredentials? = null

    private data class CachedCredentials(
        val creds: SigV4Signer.Credentials,
        val expirationEpochSec: Long,
    )

    override suspend fun categorize(batch: List<InstalledApp>): List<CategorizedApp> {
        if (batch.isEmpty()) return emptyList()

        val creds = getValidCredentials()
        val payload = json.encodeToString(
            CategorizeRequest.serializer(),
            CategorizeRequest(apps = batch.map { ApiApp(it.appName, it.packageName) })
        )
        val bodyBytes = payload.toByteArray(Charsets.UTF_8)
        val url = apiUrl.toHttpUrl()
        val signedHeaders = signer.signPost(url, bodyBytes, creds)

        val builder = Request.Builder()
            .url(url)
            .post(bodyBytes.toRequestBody("application/json".toMediaType()))
        signedHeaders.forEach { (k, v) -> builder.header(k, v) }

        val response = withContext(Dispatchers.IO) {
            http.newCall(builder.build()).execute()
        }

        response.use { resp ->
            val bodyText = resp.body?.string().orEmpty()
            when (resp.code) {
                200 -> return parseSuccess(bodyText, batch)
                429 -> {
                    val scope = runCatching {
                        json.decodeFromString(RateLimitBody.serializer(), bodyText).scope
                    }.getOrNull() ?: "unknown"
                    throw RateLimitException(scope)
                }
                503 -> throw KillSwitchException()
                else -> throw RuntimeException("API ${resp.code}: ${bodyText.take(300)}")
            }
        }
    }

    private fun parseSuccess(body: String, batch: List<InstalledApp>): List<CategorizedApp> {
        val parsed = runCatching {
            json.decodeFromString(CategorizeResponse.serializer(), body)
        }.getOrNull()
        val byPkg = parsed?.results.orEmpty().associateBy { it.packageName }
        return batch.map { app ->
            val r = byPkg[app.packageName]
            CategorizedApp(
                appName = app.appName,
                packageName = app.packageName,
                category = r?.category ?: "Other",
                source = r?.source ?: "bedrock",
            )
        }
    }

    private suspend fun getValidCredentials(): SigV4Signer.Credentials = credentialMutex.withLock {
        val now = System.currentTimeMillis() / 1000
        val cached = cachedCredentials
        if (cached != null && cached.expirationEpochSec - now > 60) {
            return@withLock cached.creds
        }

        CognitoIdentityClient { region = this@BedrockAppCategorizer.region }.use { client ->
            val getIdResp = client.getId(GetIdRequest { identityPoolId = this@BedrockAppCategorizer.identityPoolId })
            val identityId = getIdResp.identityId ?: error("getId returned null identityId")

            val credResp = client.getCredentialsForIdentity(
                GetCredentialsForIdentityRequest { this.identityId = identityId }
            )
            val c = credResp.credentials ?: error("getCredentialsForIdentity returned null")
            val creds = SigV4Signer.Credentials(
                accessKeyId = c.accessKeyId ?: error("missing accessKeyId"),
                secretAccessKey = c.secretKey ?: error("missing secretKey"),
                sessionToken = c.sessionToken,
            )
            val expiration = c.expiration?.epochSeconds ?: (now + 3600)
            cachedCredentials = CachedCredentials(creds, expiration)
            creds
        }
    }

    @Serializable
    private data class CategorizeRequest(val apps: List<ApiApp>)

    @Serializable
    private data class ApiApp(val appName: String, val packageName: String)

    @Serializable
    private data class CategorizeResponse(val results: List<ApiResult> = emptyList())

    @Serializable
    private data class ApiResult(
        val appName: String = "",
        val packageName: String = "",
        val category: String = "Other",
        val source: String = "bedrock",
    )

    @Serializable
    private data class RateLimitBody(val error: String = "", val scope: String = "unknown")
}
