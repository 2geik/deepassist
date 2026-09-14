package com.deepassist.data

import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import com.deepassist.util.DeviceUtils
import com.deepassist.util.PermissionsHelper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Local mirror of the system call log in `files/call_logs.json`, so history
 * older than what the provider returns survives. Refreshed incrementally from
 * the ContentResolver; capped at [MAX_ENTRIES].
 */
class CallLogStore private constructor(private val file: File) {

    private val gson = Gson()
    private val entries = CopyOnWriteArrayList<CallLogEntry>()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        runCatching {
            val text = file.readText()
            if (text.isBlank()) return
            val type = object : TypeToken<List<CallLogEntry>>() {}.type
            gson.fromJson<List<CallLogEntry>>(text, type)?.let { entries.addAll(it) }
        }.onFailure {
            Log.w(TAG, "Corrupted call_logs.json, starting fresh: ${it.message}")
        }
    }

    @Synchronized
    private fun persist() {
        runCatching {
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeText(gson.toJson(entries.toList()))
            tmp.renameTo(file)
            tmp.delete()
        }
    }

    /** Pulls new rows from the system call log. Returns how many entries were added. */
    @Synchronized
    fun refreshFromContentResolver(context: Context): Int {
        ensureLoaded()
        if (!PermissionsHelper.hasCallLog(context)) {
            Log.w(TAG, "READ_CALL_LOG permission not granted, cannot refresh")
            return 0
        }
        val contactNames = loadContactNames(context)
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )
        val existingIds = entries.map { it.id }.toSet()
        var added = 0

        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, null, null, "${CallLog.Calls.DATE} DESC"
            )
            if (cursor == null) {
                Log.w(TAG, "ContentResolver returned null cursor — using cached data only")
                return 0
            }
            cursor.use { c ->
                var fetched = 0
                while (c.moveToNext() && fetched < MAX_FETCH_ROWS) {
                    fetched++
                    val id = c.getString(0) ?: continue
                    if (id in existingIds) continue
                    val number = DeviceUtils.normalizePhoneNumber(c.getString(1) ?: "Bilinmeyen")
                    val type = when (c.getInt(2)) {
                        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                        CallLog.Calls.MISSED_TYPE -> "MISSED"
                        CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                        CallLog.Calls.BLOCKED_TYPE -> "BLOCKED"
                        else -> "OTHER"
                    }
                    entries.add(
                        0,
                        CallLogEntry(
                            id = id,
                            contactName = contactNames[number],
                            phoneNumber = number,
                            callType = type,
                            timestamp = c.getLong(3),
                            duration = c.getInt(4).toLong()
                        )
                    )
                    added++
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Call log access denied: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh from ContentProvider: ${e.message}")
        }

        // Keep newest first so trimming drops the oldest calls.
        if (added > 0) entries.sortByDescending { it.timestamp }
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
        if (added > 0) {
            persist()
            Log.d(TAG, "Added $added new call log entries (total: ${entries.size})")
        }
        return added
    }

    @Synchronized
    fun size(): Int {
        ensureLoaded()
        return entries.size
    }

    /** Newest first. [filter] matches contact name or number, case-insensitively. */
    @Synchronized
    fun query(
        filter: String? = null,
        callType: String? = null,
        limit: Int = 50,
        startTime: Long? = null,
        endTime: Long? = null
    ): List<CallLogEntry> {
        ensureLoaded()
        val f = filter?.lowercase(Locale.ROOT)
        return entries.asSequence()
            .sortedByDescending { it.timestamp }
            .filter { e ->
                f == null ||
                    (e.contactName?.lowercase(Locale.ROOT) ?: "").contains(f) ||
                    e.phoneNumber.lowercase(Locale.ROOT).contains(f)
            }
            .filter { e -> callType == null || e.callType.equals(callType, ignoreCase = true) }
            .filter { e -> startTime == null || e.timestamp >= startTime }
            .filter { e -> endTime == null || e.timestamp <= endTime }
            .take(limit)
            .toList()
    }

    @Synchronized
    fun forceRefresh(context: Context): Int {
        entries.clear()
        loaded = true
        file.delete()
        return refreshFromContentResolver(context)
    }

    /** Normalized number → longest display name seen for it. */
    private fun loadContactNames(context: Context): Map<String, String> {
        if (!PermissionsHelper.hasContacts(context)) return emptyMap()
        val map = LinkedHashMap<String, String>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val number = DeviceUtils.normalizePhoneNumber(c.getString(1) ?: continue)
                    if (number.length < 7) continue
                    val existing = map[number]
                    if (existing == null || name.length > existing.length) map[number] = name
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load contacts for name resolution: ${e.message}")
        }
        return map
    }

    companion object {
        private const val TAG = "CallLogStore"
        private const val MAX_ENTRIES = 500
        private const val MAX_FETCH_ROWS = 200

        @Volatile
        private var instance: CallLogStore? = null

        fun get(context: Context): CallLogStore =
            instance ?: synchronized(this) {
                instance ?: CallLogStore(File(context.filesDir, "call_logs.json")).also { instance = it }
            }
    }
}
