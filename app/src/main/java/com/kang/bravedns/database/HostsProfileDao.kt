package com.kang.bravedns.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface HostsProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(profile: HostsProfile): Long

    @Update
    fun update(profile: HostsProfile): Int

    @Query("SELECT * FROM HostsProfile ORDER BY priority ASC, updatedAt DESC")
    fun getAll(): List<HostsProfile>

    @Query("SELECT * FROM HostsProfile WHERE enabled = 1 ORDER BY priority ASC, updatedAt DESC")
    fun getActiveProfiles(): List<HostsProfile>

    @Query("SELECT * FROM HostsProfile WHERE id = :id")
    fun getById(id: Long): HostsProfile?

    @Query("DELETE FROM HostsProfile WHERE id = :id")
    fun deleteById(id: Long): Int

    @Query("UPDATE HostsProfile SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE HostsProfile SET enabled = 0, updatedAt = :updatedAt WHERE id != :id")
    fun disableOthers(id: Long, updatedAt: Long = System.currentTimeMillis()): Int
}
