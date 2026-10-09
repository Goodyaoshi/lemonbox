package com.goodyaoshi.lemonbox.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.goodyaoshi.lemonbox.data.local.entity.Location
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationDao {

    @Query("SELECT * FROM locations WHERE deletedAt IS NULL ORDER BY name ASC")
    fun getAllLocations(): Flow<List<Location>>

    @Query("SELECT * FROM locations WHERE deletedAt IS NULL AND parentId IS NULL ORDER BY name ASC")
    fun getRootLocations(): Flow<List<Location>>

    @Query("SELECT * FROM locations WHERE deletedAt IS NULL AND parentId = :parentId ORDER BY name ASC")
    fun getSubLocations(parentId: Long): Flow<List<Location>>

    @Query("SELECT * FROM locations WHERE id = :id")
    suspend fun getLocationById(id: Long): Location?

    /** 全量快照（含墓碑），供备份/同步合并使用。 */
    @Query("SELECT * FROM locations ORDER BY id ASC")
    suspend fun getAllLocationsSnapshot(): List<Location>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(location: Location): Long

    @Update
    suspend fun update(location: Location)

    @Delete
    suspend fun delete(location: Location)

    @Query("SELECT COUNT(*) FROM locations")
    suspend fun getCount(): Int

    @Query("DELETE FROM locations")
    suspend fun deleteAll()

    /** 同一父级下是否已存在同名位置（忽略大小写），供新增去重。 */
    @Query(
        "SELECT COUNT(*) FROM locations " +
            "WHERE deletedAt IS NULL AND parentId IS :parentId AND name = :name COLLATE NOCASE"
    )
    suspend fun countByNameAndParent(name: String, parentId: Long?): Int

    /** 把重复位置的下级位置改挂到保留项，供去重清理使用。 */
    @Query("UPDATE locations SET parentId = :toId WHERE parentId = :fromId AND id != :toId")
    suspend fun reassignChildren(fromId: Long, toId: Long)

    /** 把挂在重复位置上的物品改挂到保留项，供去重清理使用。 */
    @Query("UPDATE items SET locationId = :toId WHERE locationId = :fromId")
    suspend fun reassignItemsToLocation(fromId: Long, toId: Long)

    /** 软删除（写墓碑），去重清理用；保留记录以便把删除同步给其他设备。 */
    @Query("UPDATE locations SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id")
    suspend fun softDeleteById(id: Long, deletedAt: Long)
}
