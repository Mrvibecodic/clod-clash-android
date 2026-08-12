package com.github.kr328.clash.service.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.service.data.migrations.LEGACY_MIGRATION
import com.github.kr328.clash.service.data.migrations.MIGRATIONS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.room.Database as DB

@DB(
    version = 2,
    entities = [Imported::class, Pending::class, Selection::class],
    exportSchema = false,
)
abstract class Database : RoomDatabase() {
    abstract fun openImportedDao(): ImportedDao
    abstract fun openPendingDao(): PendingDao
    abstract fun openSelectionProxyDao(): SelectionDao

    companion object {
        /**
         * Один экземпляр на процесс, живёт столько же, сколько процесс.
         *
         * Апстрим держал базу за `SoftReference`: при нехватке памяти сборщик
         * очищал ссылку, и следующий же запрос открывал файл SQLite заново.
         * Экономия при этом мнимая — `RoomDatabase` без открытых курсоров
         * занимает килобайты, — а цена реальная: повторное открытие идёт
         * с миграциями и с блокировкой файла, а у нас к базе ходят ДВА
         * процесса (приложение и служба в `:background`), и второй в этот
         * момент вполне может писать.
         *
         * `by lazy` синхронизирован по умолчанию, отдельный `@Synchronized`
         * геттер больше не нужен.
         */
        val database: Database by lazy { open(Global.application) }

        private fun open(context: Context): Database {
            return Room.databaseBuilder(
                context.applicationContext,
                Database::class.java,
                "profiles"
            ).addMigrations(*MIGRATIONS).build()
        }

        init {
            Global.launch(Dispatchers.IO) {
                LEGACY_MIGRATION(Global.application)
            }
        }
    }
}
