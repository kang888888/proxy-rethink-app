package com.kang.bravedns.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "HostsProfileSync")
data class HostsProfileSync(
    @PrimaryKey val profileId: Long,
    val remoteUrl: String = "",
    val etag: String = "",
    val contentHash: String = "",
    val lastSuccessTs: Long = 0,
    val lastError: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
