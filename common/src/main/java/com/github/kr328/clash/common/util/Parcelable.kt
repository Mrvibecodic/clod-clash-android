package com.github.kr328.clash.common.util

import android.os.Binder
import android.os.Parcel
import android.os.Parcelable
import android.os.RemoteException

private class SliceParcelableListBpBinder(val list: List<Parcelable>, val flags: Int) : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, tFlags: Int): Boolean {
        when (code) {
            TRANSACTION_GET_ITEMS -> {
                reply ?: return false

                val offset = data.readInt()
                val chunk = data.readInt()

                val end = (offset + chunk).coerceAtMost(list.size)

                reply.writeInt(end - offset)

                for (i in offset until end) {
                    list[i].writeToParcel(reply, flags)
                }

                return true
            }
        }

        return super.onTransact(code, data, reply, tFlags)
    }

    companion object {
        const val TRANSACTION_GET_ITEMS = 10
    }
}

fun <T : Parcelable> List<T>.writeToParcelSlice(parcel: Parcel, flags: Int) {
    val bp = SliceParcelableListBpBinder(this, flags)

    parcel.writeInt(size)
    parcel.writeStrongBinder(bp)
}

fun <T : Parcelable> Parcelable.Creator<T>.createListFromParcelSlice(
    parcel: Parcel,
    flags: Int,
    chunk: Int,
): List<T> {
    val total = parcel.readInt()
    val remote = parcel.readStrongBinder()
    val result = ArrayList<T>(total.coerceIn(0, chunk))

    var offset = 0

    while (offset < total) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()

        try {
            data.writeInt(offset)
            data.writeInt(chunk)

            if (!remote.transact(
                    SliceParcelableListBpBinder.TRANSACTION_GET_ITEMS,
                    data,
                    reply,
                    flags
                )
            ) {
                // Оборвать чтение молча нельзя: короткий список от полного
                // неотличим, и вызывающий примет обрывок за настоящий состав.
                throw RemoteException("slice list rejected at $offset of $total")
            }

            // Отрицательная длина приходит, когда отдающая сторона упала на записи
            // порции: сдвиг назад увёл бы чтение в вечный круг.
            val size = reply.readInt()

            if (size <= 0) {
                throw RemoteException("slice list truncated at $offset of $total")
            }

            repeat(size) {
                result.add(createFromParcel(reply))
            }

            offset += size
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    return result
}
