package com.example.hostossi

import android.app.Application

class MyApplication : Application() {
    val dataBase: AppDatabase by lazy {
        AppDatabase.getDatabase(this)
    }


}