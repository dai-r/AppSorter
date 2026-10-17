package dev.dair.appsorter.categorizer

import aws.sdk.kotlin.services.cognitoidentity.CognitoIdentityClient
import aws.sdk.kotlin.services.cognitoidentity.model.GetCredentialsForIdentityRequest
import aws.sdk.kotlin.services.cognitoidentity.model.GetIdRequest
import aws.sdk.kotlin.services.cognitoidentity.model.NotAuthorizedException
import aws.sdk.kotlin.services.cognitoidentity.model.ResourceNotFoundException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 保存済みの identityId が使えなくなった（Pool 側で削除・無効化された等） */
internal class IdentityInvalidException(cause: Throwable? = null) : RuntimeException("cognito identity is no longer valid", cause)

/** Cognito Identity の呼び出し口。テストでフェイクに差し替えるため SDK を直接触らせない */
internal interface CognitoApi {
    suspend fun getId(identityPoolId: String): String

    /** identityId が無効なら [IdentityInvalidException] を投げる */
    suspend fun getCredentials(identityId: String): TemporaryCredentials
}

internal data class TemporaryCredentials(
    val creds: SigV4Signer.Credentials,
    val expirationEpochSec: Long?,
)

internal class SdkCognitoApi(private val region: String) : CognitoApi {
    override suspend fun getId(identityPoolId: String): String =
        CognitoIdentityClient { region = this@SdkCognitoApi.region }.use { client ->
            client.getId(GetIdRequest { this.identityPoolId = identityPoolId }).identityId
                ?: error("getId returned null identityId")
        }

    override suspend fun getCredentials(identityId: String): TemporaryCredentials =
        CognitoIdentityClient { region = this@SdkCognitoApi.region }.use { client ->
            val resp = try {
                client.getCredentialsForIdentity(GetCredentialsForIdentityRequest { this.identityId = identityId })
            } catch (e: ResourceNotFoundException) {
                throw IdentityInvalidException(e)
            } catch (e: NotAuthorizedException) {
                throw IdentityInvalidException(e)
            }
            val c = resp.credentials ?: error("getCredentialsForIdentity returned null")
            TemporaryCredentials(
                creds = SigV4Signer.Credentials(
                    accessKeyId = c.accessKeyId ?: error("missing accessKeyId"),
                    secretAccessKey = c.secretKey ?: error("missing secretKey"),
                    sessionToken = c.sessionToken,
                ),
                expirationEpochSec = c.expiration?.epochSeconds,
            )
        }
}

/**
 * 未認証 identity の一時クレデンシャルを返す。
 * identityId は [IdentityIdStore] に保存して再利用し、GetId は初回（と保存 ID が無効になった時）だけ呼ぶ。
 */
internal class CognitoCredentialsProvider(
    private val identityPoolId: String,
    private val api: CognitoApi,
    private val store: IdentityIdStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val mutex = Mutex()
    private var cached: TemporaryCredentials? = null

    suspend fun get(): SigV4Signer.Credentials = mutex.withLock {
        val now = nowEpochSec()
        cached?.let { c ->
            val exp = c.expirationEpochSec
            if (exp != null && exp - now > 60) return@withLock c.creds
        }

        val stored = withContext(ioDispatcher) { store.load(identityPoolId) }
        val fresh = if (stored != null) {
            try {
                api.getCredentials(stored)
            } catch (e: IdentityInvalidException) {
                // 保存 ID が無効になった場合だけ、1回限り取り直す
                withContext(ioDispatcher) { store.clear() }
                api.getCredentials(newIdentityId())
            }
        } else {
            api.getCredentials(newIdentityId())
        }

        val withExpiry = if (fresh.expirationEpochSec == null) fresh.copy(expirationEpochSec = now + 3600) else fresh
        cached = withExpiry
        withExpiry.creds
    }

    private suspend fun newIdentityId(): String {
        val id = api.getId(identityPoolId)
        withContext(ioDispatcher) { store.save(identityPoolId, id) }
        return id
    }
}
