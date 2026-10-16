package com.github.kr328.clash.common.util

import android.content.Context
import java.io.File

val Context.clashDir: File
    get() = filesDir.resolve("clash")
