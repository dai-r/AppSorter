package dev.dair.appsorter.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AppCategoryEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppSorterDatabase : RoomDatabase() {
    abstract fun appCategoryDao(): AppCategoryDao

    companion object {
        fun build(context: Context): AppSorterDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppSorterDatabase::class.java,
                "app_sorter.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE app_categories ADD COLUMN locked INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
