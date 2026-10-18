package dev.dair.appsorter.categorizer

import dev.dair.appsorter.model.CategorizedApp
import dev.dair.appsorter.model.InstalledApp

/**
 * primary が失敗したバッチだけ fallback で分類する。
 * 画面に「実際にどちらで分類したか」を出すため、フォールバックした件数と最後の失敗理由を記録する。
 * スキャン開始時に [reset] を呼ぶこと。
 */
internal class FallbackAppCategorizer(
    private val primary: AppCategorizer,
    private val fallback: AppCategorizer,
    private val onFallback: (Throwable) -> Unit = {},
) : AppCategorizer {

    /** フォールバックした原因の分類。表示文言の出し分けに使う */
    enum class Reason { DAILY_LIMIT, MONTHLY_LIMIT, SERVICE_STOPPED, ERROR }

    @Volatile
    var fallbackCount: Int = 0
        private set

    @Volatile
    var lastReason: Reason? = null
        private set

    fun reset() {
        fallbackCount = 0
        lastReason = null
    }

    override suspend fun categorize(batch: List<InstalledApp>): List<CategorizedApp> =
        runCatching { primary.categorize(batch) }.getOrElse {
            onFallback(it)
            fallbackCount += batch.size
            lastReason = reasonOf(it)
            fallback.categorize(batch)
        }

    private fun reasonOf(t: Throwable): Reason = when (t) {
        is BedrockAppCategorizer.RateLimitException -> when (t.scope) {
            "monthly" -> Reason.MONTHLY_LIMIT
            // global（全体の日次上限）も利用者から見れば「今日はもう使えない」なので日次扱い
            else -> Reason.DAILY_LIMIT
        }
        is BedrockAppCategorizer.KillSwitchException -> Reason.SERVICE_STOPPED
        else -> Reason.ERROR
    }
}
