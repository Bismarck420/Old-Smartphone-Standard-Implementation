package com.example.hostossi

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database (entities = [Project::class, Module::class, Peripheral::class], version = 1)
@TypeConverters (Converters::class)
abstract class AppDatabase : RoomDatabase(){
    abstract fun projectDao() : ProjectDao
    abstract fun moduleDao() : ModuleDao
    abstract fun peripheralDao() : PeripheralDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {

            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mainDatabase"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}