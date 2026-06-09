package com.example.db

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "favorite_channels")
data class FavoriteChannel(
    @PrimaryKey val id: String,
    val title: String,
    val group: String,
    val logoUrl: String,
    val streamUrl: String
)

@Entity(tableName = "watch_history")
data class WatchHistory(
    @PrimaryKey val id: String,
    val title: String,
    val group: String,
    val logoUrl: String,
    val streamUrl: String,
    val timestamp: Long
)

@Dao
interface ChannelDao {
    @Query("SELECT * FROM favorite_channels")
    fun getAllFavorites(): Flow<List<FavoriteChannel>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(channel: FavoriteChannel)

    @Delete
    suspend fun deleteFavorite(channel: FavoriteChannel)
    
    @Query("SELECT EXISTS(SELECT * FROM favorite_channels WHERE id = :id)")
    fun isFavorite(id: String): Flow<Boolean>

    @Query("SELECT * FROM watch_history ORDER BY timestamp DESC")
    fun getWatchHistory(): Flow<List<WatchHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWatchHistory(history: WatchHistory)
    
    @Query("DELETE FROM watch_history")
    suspend fun clearHistory()
}

@Database(entities = [FavoriteChannel::class, WatchHistory::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smtv_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
