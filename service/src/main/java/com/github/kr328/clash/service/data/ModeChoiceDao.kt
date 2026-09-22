package com.github.kr328.clash.service.data

import androidx.room.*
import com.github.kr328.clash.core.model.TunnelState
import java.util.*

@Dao
@TypeConverters(Converters::class)
interface ModeChoiceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setChoice(choice: ModeChoice)

    @Query("DELETE FROM profile_modes WHERE uuid = :uuid")
    suspend fun removeChoice(uuid: UUID)

    @Query("SELECT mode FROM profile_modes WHERE uuid = :uuid")
    suspend fun queryChoice(uuid: UUID): TunnelState.Mode?
}
