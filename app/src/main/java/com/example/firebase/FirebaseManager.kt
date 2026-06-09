package com.example.firebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.FirebaseDatabase

object FirebaseManager {
    fun init(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApiKey("AIzaSyCfwz5irJzMy1UGzVhqb4rmqL4z-jeeJzA")
                .setApplicationId("1:1080849676320:web:1faa3502ad7899c6192445")
                .setDatabaseUrl("https://minerx-market-default-rtdb.firebaseio.com")
                .setProjectId("minerx-market")
                .setStorageBucket("minerx-market.firebasestorage.app")
                .build()
            FirebaseApp.initializeApp(context, options)
        }
    }

    fun getDatabase(): FirebaseDatabase {
        return FirebaseDatabase.getInstance()
    }
}
