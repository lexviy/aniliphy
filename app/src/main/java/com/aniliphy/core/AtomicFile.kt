package com.aniliphy.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

fun File.writeTextAtomically(text: String) {
    val folder = absoluteFile.parentFile ?: throw IOException("No parent folder for $this")
    folder.mkdirs()
    val temp = File.createTempFile("$name.", ".tmp", folder)
    try {
        FileOutputStream(temp).use { out ->
            out.write(text.toByteArray())
            out.flush()
            out.fd.sync()
        }
        Files.move(
            temp.toPath(),
            absoluteFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } finally {
        temp.delete()
    }
}

fun File.readTextOrNull(): String? = try {
    readText()
} catch (_: IOException) {
    null
}
