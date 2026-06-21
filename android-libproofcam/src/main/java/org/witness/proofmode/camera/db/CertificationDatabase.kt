package org.witness.proofmode.camera.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CertificationRecord::class], version = 1, exportSchema = false)
abstract class CertificationDatabase : RoomDatabase() {
    abstract fun certificationDao(): CertificationDao

    companion object {
        @Volatile private var INSTANCE: CertificationDatabase? = null

        fun get(context: Context): CertificationDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CertificationDatabase::class.java,
                    "certifications.db"
                ).build().also { INSTANCE = it }
            }
    }
}
