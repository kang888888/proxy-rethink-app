package com.kang.bravedns.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "HostsProfile")
data class HostsProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val sourceType: String = "mixed",
    val updatedAt: Long = System.currentTimeMillis()
)
