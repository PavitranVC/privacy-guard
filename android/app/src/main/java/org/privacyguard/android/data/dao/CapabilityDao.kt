package org.privacyguard.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.privacyguard.android.data.entity.Capability

@Dao
interface CapabilityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(capability: Capability)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(capabilities: List<Capability>)

    @Query("SELECT * FROM capability WHERE op = :op LIMIT 1")
    suspend fun getCapability(op: String): Capability?

    @Query("SELECT * FROM capability")
    suspend fun getAll(): List<Capability>

    @Query("SELECT supported FROM capability WHERE op = :op LIMIT 1")
    suspend fun isSupported(op: String): Boolean?
}
