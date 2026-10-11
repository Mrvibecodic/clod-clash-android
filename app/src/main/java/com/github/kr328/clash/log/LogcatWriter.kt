package com.github.kr328.clash.log

import android.content.Context
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.util.logsDir
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Date
import java.util.Locale

class LogcatWriter(private val target: File, private val limit: Long = MAX_BYTES) : AutoCloseable {
    constructor(context: Context) : this(context.logsDir.resolve(LogFile.generate().fileName))

    private var output = FileOutputStream(target)

    private var written = 0L

    private var lines = 0

    // Конец начала записи (версия, настройки): фиксируется при первом ужатии,
    // иначе следующие ужатия тянули бы в него прежние отметки и куски хвоста
    private var head: Long? = null

    var failure: IOException? = null
        private set

    // Копии от ужатий, прерванных смертью процесса: в список журналов они не
    // попадают. Свежую не трогаем — её, может быть, ещё пишет прежняя запись
    init {
        val stale = System.currentTimeMillis() - STALE_TRIM_MS

        target.parentFile?.listFiles()
            ?.filter { it.name.endsWith(TRIM_SUFFIX) && it.lastModified() < stale }
            ?.forEach { it.delete() }
    }

    override fun close() {
        output.close()
    }

    // false — файл не удалось ужать (причина в failure), запись дальше не идёт
    fun appendMessage(message: LogMessage): Boolean {
        val line = encode(message)

        // Файл удалили из списка журналов, пока шла запись: строки уходили бы
        // в никуда до самого предела
        if (++lines % EXISTS_CHECK_LINES == 0 && !target.exists()) {
            reopen()
        }

        if (written + line.size > limit && !trim()) return false

        write(line)

        return true
    }

    fun appendLast(message: LogMessage) {
        write(encode(message))
    }

    // Без буфера: запись логов включают ради падения, а буфер как раз падение и теряет.
    private fun write(line: ByteArray) {
        output.write(line)

        written += line.size
    }

    private fun reopen() {
        output.close()

        target.parentFile?.mkdirs()

        if (!target.exists()) head = null

        output = FileOutputStream(target, true)

        written = target.length()
    }

    // Файл упёрся в предел: остаются начало записи и свежая половина, между
    // ними — отметка, что середина выброшена. Ловят обычно то, что случилось
    // перед остановкой записи, а не в первые часы. После сбоя файл снова открыт:
    // строка о причине остановки должна в него попасть
    private fun trim(): Boolean {
        output.close()

        val temp = File(target.path + TRIM_SUFFIX)

        val trimmed = try {
            if (target.exists()) {
                RandomAccessFile(target, "r").use { source ->
                    val length = source.length()
                    val start = head ?: lineBoundaryBefore(source, minOf(HEAD_BYTES, limit / 4, length)).also { head = it }
                    val tail = maxOf(start, lineBoundaryAfter(source, length - limit / 2))

                    FileOutputStream(temp).use { out ->
                        source.channel.transferFully(0, start, out)

                        out.write(encode(LogMessage(LogMessage.Level.Warning, TRIMMED, timeAt(source, tail))))

                        source.channel.transferFully(tail, length - tail, out)
                    }
                }

                // Удалённый посреди ужатия файл не возвращается старой копией (кроме
                // удаления ровно между проверкой и заменой — средствами File не закрыть)
                if (target.exists() && !temp.renameTo(target)) throw IOException("Replace $target with trimmed copy failed")
            }

            true
        } catch (e: IOException) {
            // Файл исчез посреди ужатия («Удалить все»): это не сбой, запись
            // пойдёт в новый файл
            val vanished = !target.exists()

            if (!vanished) failure = e

            vanished
        } finally {
            temp.delete()
        }

        reopen()

        return trimmed
    }

    private fun encode(message: LogMessage): ByteArray =
        String.format(Locale.ROOT, FORMAT, message.time.time, message.level.name, message.message)
            .toByteArray()

    companion object {
        const val MAX_BYTES = 25L * 1024 * 1024

        private const val HEAD_BYTES = 16L * 1024

        private const val TRIMMED = "[APP] log file limit reached, older lines dropped"

        private const val TRIM_SUFFIX = ".trim"

        private const val STALE_TRIM_MS = 10L * 60 * 1000

        private const val EXISTS_CHECK_LINES = 256

        private const val EPOCH_MS_DIGITS = 12

        private const val FORMAT = "%d:%s:%s\n"

        private const val SCAN_BYTES = 64 * 1024

        private fun lineBoundaryBefore(file: RandomAccessFile, end: Long): Long {
            if (end <= 0) return 0

            val buffer = ByteArray(end.toInt())

            file.seek(0)
            file.readFully(buffer)

            return (buffer.lastIndexOf('\n'.code.toByte()) + 1).toLong()
        }

        private fun lineBoundaryAfter(file: RandomAccessFile, from: Long): Long {
            val length = file.length()

            if (from <= 0) return 0

            var position = from - 1
            val buffer = ByteArray(SCAN_BYTES)

            while (position < length) {
                file.seek(position)

                val read = file.read(buffer)
                if (read <= 0) break

                val index = (0 until read).firstOrNull { buffer[it] == '\n'.code.toByte() }
                if (index != null) return position + index + 1

                position += read
            }

            return length
        }

        // Время отметке об обрезке — от первой строки хвоста со временем, чтобы
        // порядок не ломался: хвост может начаться с продолжения многострочного
        // сообщения, а в нём бывают «число:» (порт, IPv6, часы) — время только
        // в миллисекундах эпохи
        private fun timeAt(file: RandomAccessFile, at: Long): Date {
            val buffer = ByteArray(SCAN_BYTES)

            file.seek(at)

            val read = file.read(buffer).coerceAtLeast(0)
            val time = String(buffer, 0, read, Charsets.UTF_8).lineSequence()
                .firstNotNullOfOrNull { line ->
                    line.substringBefore(':', "").takeIf { it.length >= EPOCH_MS_DIGITS }?.toLongOrNull()
                }

            return Date(time ?: System.currentTimeMillis())
        }

        private fun java.nio.channels.FileChannel.transferFully(from: Long, count: Long, out: FileOutputStream) {
            var done = 0L

            while (done < count) {
                val moved = transferTo(from + done, count - done, out.channel)
                if (moved <= 0) throw IOException("Copy log file stalled")

                done += moved
            }
        }
    }
}
