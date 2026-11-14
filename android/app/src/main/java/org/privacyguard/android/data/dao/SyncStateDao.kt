package org.privacyguard.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.privacyguard.android.data.entity.SyncState

@Dao
interface SyncStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(syncState: SyncState)

    @Query("SELECT * FROM sync_state WHERE id = 1 LIMIT 1")
    suspend fun get(): SyncState?
}
