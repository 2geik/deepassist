package com.deepassist.tools

import android.Manifest
import android.content.ContentProviderOperation
import android.content.OperationApplicationException
import android.content.pm.PackageManager
import android.os.RemoteException
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.RawContacts
import com.deepassist.data.ToolProperty
import com.deepassist.data.ToolResult
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RenameContactTool : Tool() {

    override val name = "rename_contact"
    override val description =
        "Rehberdeki bir kişinin adını değiştirir. Kişi adıyla arar ve yeni isimle günceller."

    override val parameters = mapOf(
        "current_name" to ToolProperty(
            type = "string",
            description = "Değiştirilecek kişinin şu anki adı"
        ),
        "new_name" to ToolProperty(
            type = "string",
            description = "Kişinin yeni adı (ad soyad)"
        )
    )

    override val required = listOf("current_name", "new_name")
    override val waitForSpeech = false

    override fun dynamicThinkingPhrase(args: JsonObject): String? {
        val current = args.optString("current_name") ?: "kişi"
        val new = args.optString("new_name") ?: "yeni isim"
        return "${current}'i ${new} olarak değiştiriyorum..."
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            renameContact(args)
        } catch (e: SecurityException) {
            ToolResult(false, "", error = "Kişileri değiştirme izni verilmemiş. Lütfen ayarlardan izin verin.")
        } catch (e: OperationApplicationException) {
            ToolResult(false, "", error = "Kişi güncellenirken bir hata oluştu: ${e.message}")
        } catch (e: RemoteException) {
            ToolResult(false, "", error = "Kişi güncellenirken bir iletişim hatası oluştu: ${e.message}")
        } catch (e: Exception) {
            ToolResult(false, "", error = "Beklenmeyen hata: ${e.message}")
        }
    }

    private fun renameContact(args: JsonObject): ToolResult {
        // Check permission
        if (context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult(
                false, "",
                error = "Kişileri değiştirme izni verilmemiş. Lütfen ayarlardan Rehber iznini açın."
            )
        }

        val currentName = args.optString("current_name")?.trim()
            ?: return ToolResult(false, "", error = "Mevcut kişi adı (current_name) belirtilmedi.")
        val newName = args.optString("new_name")?.trim()
            ?: return ToolResult(false, "", error = "Yeni ad (new_name) belirtilmedi.")

        // Search for the contact
        val results = ContactLookup.search(context, currentName)

        if (results.isEmpty()) {
            return ToolResult(true, "Rehberde \"$currentName\" bulunamadı.")
        }

        // Deduplicate by contact ID
        val uniqueIds = linkedSetOf<String>()
        val namesById = linkedMapOf<String, String>()
        for (r in results) {
            if (uniqueIds.add(r.id)) {
                namesById[r.id] = r.name
            }
        }

        if (uniqueIds.size > 1) {
            val listing = namesById.values.joinToString("\n") { "- $it" }
            return ToolResult(
                true,
                "Rehberde \"$currentName\" ile eşleşen birden fazla kişi bulundu:\n$listing\n\nHangisini değiştirmek istersiniz?"
            )
        }

        val contactId = uniqueIds.single()

        // Split new name into given / family
        val givenName: String
        val familyName: String?
        val lastSpace = newName.lastIndexOf(' ')
        if (lastSpace >= 0) {
            givenName = newName.substring(0, lastSpace)
            familyName = newName.substring(lastSpace + 1)
        } else {
            givenName = newName
            familyName = null
        }

        val contentResolver = context.contentResolver

        // Look for existing StructuredName row
        val dataCursor = contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId, StructuredName.CONTENT_ITEM_TYPE),
            null
        )

        val ops = ArrayList<ContentProviderOperation>()

        dataCursor?.use { cursor ->
            if (cursor.moveToFirst()) {
                // Update existing StructuredName row
                val dataId = cursor.getLong(0)
                ops.add(
                    ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                        .withSelection(
                            "${ContactsContract.Data._ID}=?",
                            arrayOf(dataId.toString())
                        )
                        .withValue(StructuredName.DISPLAY_NAME, newName)
                        .withValue(StructuredName.GIVEN_NAME, givenName)
                        .withValue(StructuredName.FAMILY_NAME, familyName ?: "")
                        .build()
                )
            }
        }

        if (ops.isEmpty()) {
            // No StructuredName row exists (number-only contact).
            // Get one RawContact ID and insert a new StructuredName row.
            val rawCursor = contentResolver.query(
                RawContacts.CONTENT_URI,
                arrayOf(RawContacts._ID),
                "${RawContacts.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            )

            rawCursor?.use { rc ->
                if (rc.moveToFirst()) {
                    val rawId = rc.getLong(0)
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                            .withValue(
                                ContactsContract.Data.MIMETYPE,
                                StructuredName.CONTENT_ITEM_TYPE
                            )
                            .withValue(StructuredName.DISPLAY_NAME, newName)
                            .withValue(StructuredName.GIVEN_NAME, givenName)
                            .withValue(StructuredName.FAMILY_NAME, familyName ?: "")
                            .build()
                    )
                }
            }

            if (ops.isEmpty()) {
                return ToolResult(
                    false, "",
                    error = "Kişiye ait kayıt bulunamadı."
                )
            }
        }

        val resultsArray = contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)

        if (resultsArray.isNotEmpty()) {
            return ToolResult(
                true,
                "'${currentName}' adı '${newName}' olarak değiştirildi."
            )
        } else {
            return ToolResult(
                true,
                "'${currentName}' adı değiştirilemedi. Kişi salt okunur olabilir."
            )
        }
    }
}
