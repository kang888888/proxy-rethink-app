package com.kang.bravedns.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface HostsProfileSyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(sync: HostsProfileSync): Long

    @Update
    fun update(sync: HostsProfileSync): Int

    @Query("SELECT * FROM HostsProfileSync WHERE profileId = :profileId")
    fun get(profileId: Long): HostsProfileSync?

    @Query("DELETE FROM HostsProfileSync WHERE profileId = :profileId")
    fun delete(profileId: Long): Int
}
