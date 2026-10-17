package dev.dair.appsorter.categorizer

import android.content.Context

/**
 * Cognito identityId の永続化。
 * 未認証の GetId は呼ぶたびに新しい identity を発行するため、保存しないと
 * 起動のたびに別人扱いになり、Lambda 側の identity 単位の回数制限が効かない。
 */
interface IdentityIdStore {
    /** 保存済みの identityId。保存時と poolId が違えば null（接続先 Pool が変わったビルドで古い ID を使わない） */
    fun load(poolId: String): String?

    /** 同期的に書き込む（呼び出し側で IO スレッドから呼ぶこと） */
    fun save(poolId: String, identityId: String)

    fun clear()
}

class SharedPrefsIdentityIdStore(context: Context) : IdentityIdStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(poolId: String): String? {
        if (prefs.getString(KEY_POOL_ID, null) != poolId) return null
        return prefs.getString(KEY_IDENTITY_ID, null)
    }

    override fun save(poolId: String, identityId: String) {
        // apply() だと書き込み前にプロセスが落ちた場合に失われ、identity がもう1つ作られるので commit()
        prefs.edit()
            .putString(KEY_POOL_ID, poolId)
            .putString(KEY_IDENTITY_ID, identityId)
            .commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val PREFS = "appsorter_cognito_identity"
        const val KEY_POOL_ID = "pool_id"
        const val KEY_IDENTITY_ID = "identity_id"
    }
}
