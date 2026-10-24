package dev.rootops.core.util

import org.json.JSONObject
import java.io.File

/**
 * Small synchronized JSON file store with atomic replacement.
 * A missing or corrupt file reads as an empty [JSONObject].
 */
class JsonStore(val file: File) {

    fun read(): JSONObject {
        synchronized(this) {
            if (!file.exists() || file.length() == 0L) return JSONObject()
            return try {
                JSONObject(file.readText())
            } catch (t: Throwable) {
                JSONObject()
            }
        }
    }

    fun write(obj: JSONObject) {
        synchronized(this) {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            val temp = File(parent ?: file.absoluteFile.parentFile, file.name + ".tmp")
            temp.writeText(obj.toString())
            if (!temp.renameTo(file)) {
                file.writeText(obj.toString())
                temp.delete()
            }
        }
    }

    fun update(block: (JSONObject) -> Unit) {
        synchronized(this) {
            val obj = read()
            block(obj)
            write(obj)
        }
    }
}
