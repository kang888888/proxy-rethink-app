package com.kang.bravedns.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface HostsEntryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entry: HostsEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(entries: List<HostsEntry>): List<Long>

    @Update
    fun update(entry: HostsEntry): Int

    @Query("SELECT * FROM HostsEntry WHERE profileId = :profileId ORDER BY domain ASC")
    fun getByProfile(profileId: Long): List<HostsEntry>

    @Query("SELECT COUNT(*) FROM HostsEntry WHERE profileId = :profileId AND enabled = 1")
    fun getEnabledCountByProfile(profileId: Long): Int

    @Query(
        "SELECT e.* FROM HostsEntry e INNER JOIN HostsProfile p ON e.profileId = p.id WHERE p.enabled = 1 AND e.enabled = 1 ORDER BY p.priority ASC, p.updatedAt DESC, e.updatedAt DESC"
    )
    fun getAllActiveEntries(): List<HostsEntry>

    @Query("DELETE FROM HostsEntry WHERE profileId = :profileId")
    fun deleteByProfile(profileId: Long): Int

    @Query("DELETE FROM HostsEntry WHERE id = :id")
    fun deleteById(id: Long): Int
}
