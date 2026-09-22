package com.github.kr328.clash.service.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.TypeConverters
import com.github.kr328.clash.core.model.TunnelState
import java.util.*

@Entity(
    tableName = "profile_modes",
    foreignKeys = [ForeignKey(
        entity = Imported::class,
        childColumns = ["uuid"],
        parentColumns = ["uuid"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.CASCADE
    )],
    primaryKeys = ["uuid"]
)
@TypeConverters(Converters::class)
data class ModeChoice(
    @ColumnInfo(name = "uuid") val uuid: UUID,
    @ColumnInfo(name = "mode") val mode: TunnelState.Mode,
)
