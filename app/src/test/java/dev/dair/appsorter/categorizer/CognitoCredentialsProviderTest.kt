package dev.dair.appsorter.categorizer

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CognitoCredentialsProviderTest {

    private val pool = "ap-northeast-1:pool-a"

    /** GetId のたびに新しい ID を発行する（未認証 Cognito と同じ挙動） */
    private class FakeCognito(
        private val invalidIds: MutableSet<String> = mutableSetOf(),
        private val expiresInSec: Long = 3600,
        private val now: () -> Long,
    ) : CognitoApi {
        var getIdCalls = 0
        val credentialCalls = mutableListOf<String>()

        override suspend fun getId(identityPoolId: String): String {
            getIdCalls++
            return "id-$getIdCalls"
        }

        override suspend fun getCredentials(identityId: String): TemporaryCredentials {
            credentialCalls += identityId
            if (identityId in invalidIds) throw IdentityInvalidException()
            return TemporaryCredentials(
                creds = SigV4Signer.Credentials("AK-$identityId", "SK", "TOKEN"),
                expirationEpochSec = now() + expiresInSec,
            )
        }

        fun invalidate(id: String) { invalidIds += id }
    }

    private class MemoryStore : IdentityIdStore {
        var poolId: String? = null
        var identityId: String? = null

        override fun load(poolId: String): String? = if (this.poolId == poolId) identityId else null
        override fun save(poolId: String, identityId: String) { this.poolId = poolId; this.identityId = identityId }
        override fun clear() { poolId = null; identityId = null }
    }

    private var clock = 1_000_000L

    private fun TestScope.provider(api: CognitoApi, store: IdentityIdStore) =
        CognitoCredentialsProvider(pool, api, store, StandardTestDispatcher(testScheduler), nowEpochSec = { clock })

    @Test
    fun 初回はGetIdを1回呼んで保存する() = runTest {
        val api = FakeCognito(now = { clock })
        val store = MemoryStore()

        val creds = provider(api, store).get()

        assertEquals(1, api.getIdCalls)
        assertEquals("id-1", store.identityId)
        assertEquals(pool, store.poolId)
        assertEquals("AK-id-1", creds.accessKeyId)
    }

    @Test
    fun アプリを再起動してもGetIdを呼ばず保存済みIDを使う() = runTest {
        val api = FakeCognito(now = { clock })
        val store = MemoryStore()

        provider(api, store).get()
        // プロセス再起動 = provider（メモリキャッシュ）を作り直す。ストアは残る
        val creds = provider(api, store).get()

        assertEquals(1, api.getIdCalls)
        assertEquals(listOf("id-1", "id-1"), api.credentialCalls)
        assertEquals("AK-id-1", creds.accessKeyId)
    }

    @Test
    fun 保存IDが無効なら1回だけ取り直す() = runTest {
        val api = FakeCognito(now = { clock })
        val store = MemoryStore().apply { save(pool, "id-old") }
        api.invalidate("id-old")

        val creds = provider(api, store).get()

        assertEquals(1, api.getIdCalls)
        assertEquals(listOf("id-old", "id-1"), api.credentialCalls)
        assertEquals("id-1", store.identityId)
        assertEquals("AK-id-1", creds.accessKeyId)
    }

    @Test
    fun 取り直したIDも無効なら例外を投げてループしない() = runTest {
        val api = FakeCognito(invalidIds = mutableSetOf("id-old", "id-1"), now = { clock })
        val store = MemoryStore().apply { save(pool, "id-old") }

        try {
            provider(api, store).get()
            fail("IdentityInvalidException が投げられるはず")
        } catch (e: IdentityInvalidException) {
            // 期待どおり
        }
        assertEquals(1, api.getIdCalls)
        assertEquals(listOf("id-old", "id-1"), api.credentialCalls)
    }

    @Test
    fun 新規取得したIDが無効でも取り直さない() = runTest {
        val api = FakeCognito(invalidIds = mutableSetOf("id-1"), now = { clock })
        val store = MemoryStore()

        try {
            provider(api, store).get()
            fail("IdentityInvalidException が投げられるはず")
        } catch (e: IdentityInvalidException) {
            // 期待どおり
        }
        assertEquals(1, api.getIdCalls)
    }

    @Test
    fun PoolIDが違えば保存済みIDを使わない() = runTest {
        val api = FakeCognito(now = { clock })
        val store = MemoryStore().apply { save("ap-northeast-1:pool-other", "id-other") }

        provider(api, store).get()

        assertEquals(1, api.getIdCalls)
        assertTrue("id-other" !in api.credentialCalls)
        assertEquals(pool, store.poolId)
    }

    @Test
    fun キャッシュが有効な間はCognitoを呼ばず期限60秒前で取り直す() = runTest {
        val api = FakeCognito(expiresInSec = 3600, now = { clock })
        val store = MemoryStore()
        val p = provider(api, store)

        p.get()
        clock += 3600 - 61
        p.get()
        assertEquals(1, api.credentialCalls.size)

        clock += 2  // 失効まで59秒
        p.get()
        assertEquals(2, api.credentialCalls.size)
        assertEquals(1, api.getIdCalls)
    }
}
