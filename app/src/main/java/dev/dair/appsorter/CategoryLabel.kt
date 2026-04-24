package dev.dair.appsorter

import android.content.Context

object CategoryLabel {
    const val ALL = "__ALL__"

    /**
     * 内部カテゴリ名 (RuleBasedAppCategorizer / Bedrock Lambda と共有) から
     * 表示用文字列リソース id を引く。
     */
    private val mapping = mapOf(
        "Communication" to R.string.category_Communication,
        "SNS" to R.string.category_SNS,
        "Mail" to R.string.category_Mail,
        "Video" to R.string.category_Video,
        "Music" to R.string.category_Music,
        "Photo" to R.string.category_Photo,
        "Game" to R.string.category_Game,
        "Shopping" to R.string.category_Shopping,
        "Payment" to R.string.category_Payment,
        "Points" to R.string.category_Points,
        "Banking" to R.string.category_Banking,
        "Crypto" to R.string.category_Crypto,
        "Work" to R.string.category_Work,
        "News" to R.string.category_News,
        "Navigation" to R.string.category_Navigation,
        "Travel" to R.string.category_Travel,
        "Mobility" to R.string.category_Mobility,
        "Food" to R.string.category_Food,
        "Health" to R.string.category_Health,
        "Education" to R.string.category_Education,
        "Utility" to R.string.category_Utility,
        "System" to R.string.category_System,
        "Other" to R.string.category_Other,
    )

    fun displayName(context: Context, internalName: String): String {
        if (internalName == ALL) return context.getString(R.string.category_all)
        val resId = mapping[internalName] ?: return internalName
        return context.getString(resId)
    }
}
