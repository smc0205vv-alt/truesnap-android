package org.witness.proofmode.camera.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CertificationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: CertificationRecord)

    @Query("SELECT * FROM my_certifications ORDER BY captureTimestampMs DESC")
    fun observeAll(): Flow<List<CertificationRecord>>

    @Query("UPDATE my_certifications SET expiresAtMs = :expiresAtMs WHERE authId = :authId")
    suspend fun updateExpiry(authId: String, expiresAtMs: Long)

    @Delete
    suspend fun delete(record: CertificationRecord)
}
