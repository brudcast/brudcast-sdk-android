package com.brudcast.sdk

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Small values and JSON files the SDK keeps between launches, in storage that is never backed up.
 */
public class BrudcastStorage internal constructor(
    private val preferences: SharedPreferences?,
    public val directory: File,
) {
    init {
        directory.mkdirs()
    }

    /**
     * Reads a string.
     *
     * @param key The key.
     * @return The stored string, or null.
     */
    public fun string(key: String): String? = preferences?.getString(key, null)

    /**
     * Stores a string, or removes the key when the value is null.
     *
     * @param key The key.
     * @param value The value.
     */
    public fun setString(key: String, value: String?) {
        preferences?.edit()?.apply {
            if (value == null) remove(key) else putString(key, value)
        }?.apply()
    }

    /**
     * Reads a boolean, false when absent.
     *
     * @param key The key.
     * @return The stored value.
     */
    public fun bool(key: String): Boolean = preferences?.getBoolean(key, false) ?: false

    /**
     * Stores a boolean.
     *
     * @param key The key.
     * @param value The value.
     */
    public fun setBool(key: String, value: Boolean) {
        preferences?.edit()?.putBoolean(key, value)?.apply()
    }

    /**
     * Reads a JSON object from a file.
     *
     * @param name The file name.
     * @return The object, or null when the file is missing or unreadable.
     */
    public fun readObject(name: String): JSONObject? = read(name)?.let {
        try {
            JSONObject(it)
        } catch (_: JSONException) {
            null
        }
    }

    /**
     * Reads a JSON array from a file.
     *
     * @param name The file name.
     * @return The array, or null when the file is missing or unreadable.
     */
    public fun readArray(name: String): JSONArray? = read(name)?.let {
        try {
            JSONArray(it)
        } catch (_: JSONException) {
            null
        }
    }

    /**
     * Writes JSON to a file atomically.
     *
     * @param name The file name.
     * @param json A `JSONObject` or `JSONArray`.
     */
    public fun write(name: String, json: Any) {
        val target = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        try {
            temporary.writeText(json.toString(), Charsets.UTF_8)
            if (!temporary.renameTo(target)) {
                target.writeText(json.toString(), Charsets.UTF_8)
                temporary.delete()
            }
        } catch (error: IOException) {
            BrudcastLogger.error("Could not write $name: ${error.message}")
        }
    }

    /**
     * Deletes a file.
     *
     * @param name The file name.
     */
    public fun remove(name: String) {
        File(directory, name).delete()
    }

    private fun read(name: String): String? {
        val file = File(directory, name)
        if (!file.exists()) return null
        return try {
            file.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            null
        }
    }

    internal fun deviceId(): String {
        val file = File(directory, "device_id")
        val existing = try {
            if (file.exists()) file.readText().trim() else ""
        } catch (_: IOException) {
            ""
        }
        if (existing.length >= 8) return existing
        val created = UUID.randomUUID().toString()
        try {
            file.writeText(created)
        } catch (error: IOException) {
            BrudcastLogger.error("Could not store the device id: ${error.message}")
        }
        return created
    }

    internal companion object {
        fun standard(context: Context): BrudcastStorage {
            val preferences = context.getSharedPreferences("com.brudcast.sdk", Context.MODE_PRIVATE)
            return BrudcastStorage(preferences, File(context.noBackupFilesDir, "brudcast"))
        }
    }
}
