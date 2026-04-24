package dev.dair.appsorter.data

import dev.dair.appsorter.model.CategorizedApp

class AppCategoryRepository(
    private val dao: AppCategoryDao
) {
    /**
     * 再スキャン結果の書き込み。locked=true の行（ユーザー手動編集済み）は
     * packageName ごとスキップしてカテゴリを保護する。
     */
    suspend fun saveApps(apps: List<CategorizedApp>) {
        val lockedPkgs = dao.getLockedPackageNames().toSet()
        dao.upsertAll(
            apps
                .filter { it.packageName !in lockedPkgs }
                .map {
                    AppCategoryEntity(
                        packageName = it.packageName,
                        appName = it.appName,
                        category = it.category,
                        source = it.source,
                        locked = false,
                    )
                }
        )
    }

    suspend fun loadApps(): List<CategorizedApp> {
        return dao.getAll().map {
            CategorizedApp(
                appName = it.appName,
                packageName = it.packageName,
                category = it.category,
                source = it.source,
            )
        }
    }

    /**
     * ユーザーの手動カテゴリ変更。以降、再スキャンでは保護される。
     */
    suspend fun overrideCategory(packageName: String, newCategory: String) {
        dao.overrideCategory(packageName, newCategory)
    }
}
