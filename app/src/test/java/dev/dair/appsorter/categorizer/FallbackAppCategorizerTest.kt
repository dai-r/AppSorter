package dev.dair.appsorter.categorizer

import dev.dair.appsorter.categorizer.FallbackAppCategorizer.Reason
import dev.dair.appsorter.model.CategorizedApp
import dev.dair.appsorter.model.InstalledApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FallbackAppCategorizerTest {

    /** source に名前を入れて返す。failWith を設定するとそのバッチは例外を投げる */
    private class FakeCategorizer(private val name: String) : AppCategorizer {
        var failWith: Throwable? = null

        override suspend fun categorize(batch: List<InstalledApp>): List<CategorizedApp> {
            failWith?.let { throw it }
            return batch.map { CategorizedApp(it.appName, it.packageName, "Other", name) }
        }
    }

    private fun apps(n: Int) = (1..n).map { InstalledApp("app$it", "pkg.$it") }

    private val primary = FakeCategorizer("bedrock")
    private val fallback = FakeCategorizer("rule")
    private val sut = FallbackAppCategorizer(primary, fallback)

    @Test
    fun 成功時はフォールバックを記録しない() = runTest {
        val result = sut.categorize(apps(3))

        assertEquals(listOf("bedrock"), result.map { it.source }.distinct())
        assertEquals(0, sut.fallbackCount)
        assertNull(sut.lastReason)
    }

    @Test
    fun 一部のバッチだけ失敗すると失敗分の件数だけ数える() = runTest {
        sut.categorize(apps(50))
        primary.failWith = BedrockAppCategorizer.RateLimitException("daily")
        val second = sut.categorize(apps(20))

        assertEquals(listOf("rule"), second.map { it.source }.distinct())
        assertEquals(20, sut.fallbackCount)
        assertEquals(Reason.DAILY_LIMIT, sut.lastReason)
    }

    @Test
    fun 失敗理由を分類する() = runTest {
        val cases = listOf(
            BedrockAppCategorizer.RateLimitException("daily") to Reason.DAILY_LIMIT,
            BedrockAppCategorizer.RateLimitException("global") to Reason.DAILY_LIMIT,
            BedrockAppCategorizer.RateLimitException("monthly") to Reason.MONTHLY_LIMIT,
            BedrockAppCategorizer.KillSwitchException() to Reason.SERVICE_STOPPED,
            RuntimeException("API busy: conflict") to Reason.ERROR,
        )
        for ((error, expected) in cases) {
            primary.failWith = error
            sut.categorize(apps(1))
            assertEquals(error.message, expected, sut.lastReason)
        }
    }

    @Test
    fun resetで前回スキャンの記録を消す() = runTest {
        primary.failWith = BedrockAppCategorizer.KillSwitchException()
        sut.categorize(apps(5))

        sut.reset()

        assertEquals(0, sut.fallbackCount)
        assertNull(sut.lastReason)
    }
}
