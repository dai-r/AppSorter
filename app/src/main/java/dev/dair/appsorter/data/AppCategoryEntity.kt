package dev.dair.appsorter.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_categories")
data class AppCategoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "app_name")
    val appName: String,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "source")
    val source: String,
    /** true の行は再スキャン時にカテゴリを更新しない（ユーザー手動編集保護用） */
    @ColumnInfo(name = "locked", defaultValue = "0")
    val locked: Boolean = false,
)
