package com.deepassist.tools

import android.content.Context
import android.provider.ContactsContract
import com.deepassist.data.ContactInfo
import com.deepassist.util.DeviceUtils
import com.deepassist.util.PermissionsHelper
import java.util.Locale

object ContactLookup {

    private val turkish = Locale("tr", "TR")

    /**
     * Searches contacts by name. Returns deduplicated results (by normalized
     * phone number), max 10 entries, most relevant to the query first.
     */
    fun search(context: Context, rawQuery: String): List<ContactInfo> {
        if (!PermissionsHelper.hasContacts(context)) return emptyList()

        val stems = DeviceUtils.turkceIsimKirp(rawQuery)

        // --- Fast path: SQL LIKE on each stem ---
        // Dedup by normalised PHONE NUMBER, keeping the longest name variant.
        val bestByNumber = linkedMapOf<String, ContactInfo>()

        for (stem in stems) {
            val batch = queryContacts(context, selection = "%$stem%")
            for (c in batch) {
                val normNumber = DeviceUtils.normalizePhoneNumber(c.phoneNumber)
                if (normNumber.length < 7) continue
                val existing = bestByNumber[normNumber]
                if (existing == null || c.name.length > existing.name.length) {
                    bestByNumber[normNumber] = ContactInfo(c.id, c.name, normNumber)
                }
            }
            if (bestByNumber.size >= 10) break
        }

        // --- Broad fallback: scan all contacts, normalize Turkish → ASCII ---
        if (bestByNumber.isEmpty()) {
            val all = queryContacts(context, selection = null)
            for (stem in stems) {
                for (c in all) {
                    if (!DeviceUtils.toAsciiTurkce(c.name).contains(stem)) continue
                    val normNumber = DeviceUtils.normalizePhoneNumber(c.phoneNumber)
                    if (normNumber.length < 7) continue
                    val existing = bestByNumber[normNumber]
                    if (existing == null || c.name.length > existing.name.length) {
                        bestByNumber[normNumber] = ContactInfo(c.id, c.name, normNumber)
                    }
                }
                if (bestByNumber.isNotEmpty()) break
            }
        }

        return bestByNumber.values.toList()
            .sortedByDescending { scoreRelevance(it.name, stems) }
            .take(10)
    }

    /**
     * Matches on earlier (less-trimmed) stems score higher, as do stems that
     * cover more of the name.
     */
    private fun scoreRelevance(name: String, stems: List<String>): Float {
        val ascii = DeviceUtils.toAsciiTurkce(name)
        var best = 0f
        stems.forEachIndexed { i, stem ->
            if (ascii.contains(stem)) {
                val stemBonus = 1f - i * 0.25f
                val lengthBonus = stem.length.toFloat() / ascii.length
                val score = 0.6f * stemBonus + 0.4f * lengthBonus
                if (score > best) best = score
            }
        }
        return best
    }

    private fun queryContacts(context: Context, selection: String?): List<ContactInfo> {
        val results = mutableListOf<ContactInfo>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection?.let { "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?" },
            selection?.let { arrayOf(it) },
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext() && results.size < 500) {
                val id = cursor.getString(0) ?: continue
                val contactName = cursor.getString(1) ?: continue
                val number = cursor.getString(2) ?: continue
                results.add(ContactInfo(id, contactName, number))
            }
        }
        return results
    }
}
