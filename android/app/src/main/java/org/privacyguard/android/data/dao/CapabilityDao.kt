package org.privacyguard.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.privacyguard.android.data.entity.Capability

@Dao
interface CapabilityDao {

    @Query("SELECT * FROM capability WHERE op = :op AND buildFingerprint = :fingerprint LIMIT 1")
    suspend fun getCapability(op: String, fingerprint: String): Capability?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(capability: Capability)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(capabilities: List<Capability>)

    @Query("SELECT * FROM capability")
    suspend fun getAllCapabilities(): List<Capability>

    @Query("SELECT * FROM capability")
    fun observeAllCapabilities(): Flow<List<Capability>>

    @Query("SELECT (supported == 1) FROM capability WHERE op = :op AND buildFingerprint = :fingerprint LIMIT 1")
    suspend fun isSupported(op: String, fingerprint: String): Boolean?
}
