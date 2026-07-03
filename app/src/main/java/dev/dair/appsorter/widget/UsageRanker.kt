package dev.dair.appsorter.widget

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * 使用頻度（直近30日の合計フォアグラウンド時間）を取得するヘルパー。
 *
 * PACKAGE_USAGE_STATS は特殊権限で、ユーザーが「設定 > 使用状況へのアクセス」で
 * 手動許可する必要がある。未許可なら hasAccess=false / scores=空Map を返し、
 * 呼び出し側は名前順にフォールバックする。
 */
object UsageRanker {

    private const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000  // 直近30日

    /** 「使用状況へのアクセス」が許可されているか。 */
    fun hasAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        // minSdk 26 対応のため非推奨 API を使用（unsafeCheckOpNoThrow は API 29+）
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * packageName → 直近30日の合計フォアグラウンド時間(ms)。
     * 未許可、または取得失敗時は空 Map（=呼び出し側で名前順フォールバック）。
     */
    fun scores(context: Context): Map<String, Long> {
        if (!hasAccess(context)) return emptyMap()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyMap()
        val end = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, end - WINDOW_MS, end)
            ?: return emptyMap()
        // 同一パッケージが複数バケットで返るので合算する
        val map = HashMap<String, Long>()
        for (s in stats) {
            map[s.packageName] = (map[s.packageName] ?: 0L) + s.totalTimeInForeground
        }
        return map
    }
}
