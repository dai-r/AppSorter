package dev.dair.appsorter.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AppCategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<AppCategoryEntity>)

    @Query("SELECT * FROM app_categories ORDER BY category ASC, app_name ASC")
    suspend fun getAll(): List<AppCategoryEntity>

    @Query("SELECT package_name FROM app_categories WHERE locked = 1")
    suspend fun getLockedPackageNames(): List<String>

    @Query(
        "UPDATE app_categories " +
            "SET category = :category, source = 'manual', locked = 1 " +
            "WHERE package_name = :packageName"
    )
    suspend fun overrideCategory(packageName: String, category: String)
}
