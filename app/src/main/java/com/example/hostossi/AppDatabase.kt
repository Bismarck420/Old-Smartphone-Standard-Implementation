package com.example.hostossi

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database (entities = [Project::class, Module::class, DeviceEntity::class, Manager::class], version = 9)
@TypeConverters (Converters::class)
abstract class AppDatabase : RoomDatabase(){
    abstract fun projectDao() : ProjectDao
    abstract fun moduleDao() : ModuleDao
    abstract fun peripheralDao() : DeviceEntityDao
    abstract fun managerDao() : ManagerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE modules ADD COLUMN project_id TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE peripherals ADD COLUMN ip_address TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE modules ADD COLUMN value REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE modules ADD COLUMN unit TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE devices ADD COLUMN sensor_type INTEGER NOT NULL DEFAULT -1")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN display_order INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE modules ADD COLUMN display_order INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE devices ADD COLUMN source_device_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE devices ADD COLUMN source_device_name TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE devices ADD COLUMN manager_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS managers (id TEXT NOT NULL PRIMARY KEY, manager_id TEXT NOT NULL, title TEXT NOT NULL, device_list TEXT NOT NULL, ip_address TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_devices_manager_id ON devices(manager_id)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE devices ADD COLUMN manager_peripheral_id TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getDatabase(context: Context): AppDatabase {

            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mainDatabase"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
