package com.kang.bravedns.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "HostsEntry",
    indices = [Index(value = ["profileId", "domain", "recordType"])]
)
data class HostsEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val domain: String,
    val recordType: String,
    val value: String,
    val enabled: Boolean = true,
    val isSuffixMatch: Boolean = false,
    val source: String = "manual",
    val updatedAt: Long = System.currentTimeMillis()
)
