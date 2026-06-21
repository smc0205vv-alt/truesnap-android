package org.witness.proofmode.camera.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "my_certifications")
data class CertificationRecord(
    @PrimaryKey val authId: String,
    val nickname: String,
    val captureTimestampMs: Long,
    val expiresAtMs: Long,
    val thumbnailBase64: String?
)
