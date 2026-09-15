package com.deepassist.tools

import android.content.Context
import android.provider.ContactsContract
import com.deepassist.data.ContactInfo
import com.deepassist.util.DeviceUtils
import com.deepassist.util.PermissionsHelper
import java.text.Normalizer

object ContactLookup {

    private const val MAX_ROWS = 20_000

    private val combiningMarks = Regex("\\p{M}+")
    private val apostrophes = Regex("['’`]")
    private val nonAlphanumeric = Regex("[^a-z0-9]+")

    /**
     * Searches contacts by name. Returns deduplicated results (by normalized
     * phone number), max 10 entries, most relevant to the query first.
     *
     * The query and every name are folded to plain ASCII before matching, so
     * "Süleyman" finds a contact saved as "Suleyman" and the other way round.
     */
    fun search(context: Context, rawQuery: String): List<ContactInfo> {
        if (!PermissionsHelper.hasContacts(context)) return emptyList()

        val query = fold(rawQuery)
        if (query.isEmpty()) return emptyList()
        val stems = stemsOf(query)
        val wordStems = query.split(' ').filter { it.length >= 2 }.map(::stemsOf)

        // Dedup by normalised PHONE NUMBER, keeping the best-scoring (then longest) name.
        val bestByNumber = linkedMapOf<String, Pair<ContactInfo, Float>>()
        for (c in queryAllContacts(context)) {
            val score = scoreRelevance(fold(c.name), stems, wordStems)
            if (score <= 0f) continue
            val normNumber = DeviceUtils.normalizePhoneNumber(c.phoneNumber)
            if (normNumber.length < 7) continue
            val existing = bestByNumber[normNumber]
            if (existing == null || score > existing.second ||
                (score == existing.second && c.name.length > existing.first.name.length)
            ) {
                bestByNumber[normNumber] = ContactInfo(c.id, c.name, normNumber) to score
            }
        }

        return bestByNumber.values
            .sortedByDescending { it.second }
            .take(10)
            .map { it.first }
    }

    /** Lowercase ASCII; apostrophes dropped ("Süleyman'ı" → "suleymani"), other punctuation → spaces. */
    private fun fold(s: String): String =
        Normalizer.normalize(DeviceUtils.toAsciiTurkce(s), Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .replace(apostrophes, "")
            .replace(nonAlphanumeric, " ")
            .trim()

    private fun stemsOf(folded: String): List<String> =
        DeviceUtils.turkceIsimKirp(folded).filter { it.length >= 2 }.distinct()

    /**
     * Whole-query matches on earlier (less-trimmed) stems score higher, as do stems
     * that cover more of the name or start one of its words. A multi-word query also
     * matches when each of its words (suffix trimmed) starts a word of the name, in
     * any order ("Yazıcı Süleyman", "Ali teyze oğlunu").
     */
    private fun scoreRelevance(name: String, stems: List<String>, wordStems: List<List<String>>): Float {
        if (name.isEmpty()) return 0f
        var best = 0f
        stems.forEachIndexed { i, stem ->
            val at = name.indexOf(stem)
            if (at >= 0) {
                val stemBonus = 1f - i * 0.25f
                val lengthBonus = stem.length.toFloat() / name.length
                val wordStartBonus = if (at == 0 || name[at - 1] == ' ') 0.1f else 0f
                best = maxOf(best, 0.6f * stemBonus + 0.4f * lengthBonus + wordStartBonus)
            }
        }
        if (wordStems.size >= 2) {
            val nameWords = name.split(' ')
            var covered = 0
            for (alternatives in wordStems) {
                val hit = alternatives.firstOrNull { alt -> nameWords.any { it.startsWith(alt) } }
                    ?: return best
                covered += hit.length
            }
            val letters = name.count { it != ' ' }.coerceAtLeast(1)
            best = maxOf(best, 0.5f + 0.4f * covered.toFloat() / letters)
        }
        return best
    }

    private fun queryAllContacts(context: Context): List<ContactInfo> {
        val results = mutableListOf<ContactInfo>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext() && results.size < MAX_ROWS) {
                val id = cursor.getString(0) ?: continue
                val contactName = cursor.getString(1) ?: continue
                val number = cursor.getString(2) ?: continue
                results.add(ContactInfo(id, contactName, number))
            }
        }
        return results
    }
}
