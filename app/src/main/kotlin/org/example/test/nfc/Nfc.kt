package org.example.test.nfc

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.util.Locale

/** One decoded NDEF record, ready for display. */
data class ParsedRecord(val kind: String, val content: String)

/** Everything we learned from a single tag tap. */
data class ScanResult(
    val uid: String,
    val chip: String,
    val technologies: List<String>,
    val ndefType: String?,
    val usedBytes: Int?,
    val maxBytes: Int?,
    val writable: Boolean?,
    val blank: Boolean,
    val records: List<ParsedRecord>,
    val error: String? = null,
    val time: Long = System.currentTimeMillis(),
)

// ---------------------------------------------------------------- reading

fun readTag(tag: Tag): ScanResult {
    val techs = tag.techList.map { it.substringAfterLast('.') }
    var message: NdefMessage? = null
    var ndefType: String? = null
    var maxSize: Int? = null
    var writable: Boolean? = null
    var error: String? = null

    val ndef = Ndef.get(tag)
    if (ndef != null) {
        try {
            ndef.connect()
            message = ndef.ndefMessage
            ndefType = ndef.type
            maxSize = ndef.maxSize
            writable = ndef.isWritable
        } catch (e: FormatException) {
            error = "Tag data is not valid NDEF."
        } catch (e: TagLostException) {
            error = "Tag moved away before it could be read."
        } catch (e: Exception) {
            error = "Read failed: ${e.message}"
        } finally {
            runCatching { ndef.close() }
        }
    }

    val records = message?.records
        ?.filter { it.tnf != NdefRecord.TNF_EMPTY }
        ?.map(::parseRecord)
        .orEmpty()

    val formatable = ndef == null && NdefFormatable.get(tag) != null

    return ScanResult(
        uid = tag.id.toHex(),
        chip = detectChip(tag),
        technologies = techs,
        ndefType = ndefType,
        usedBytes = message?.takeIf { records.isNotEmpty() }?.toByteArray()?.size ?: if (ndef != null) 0 else null,
        maxBytes = maxSize,
        writable = writable,
        blank = records.isEmpty() && error == null && (ndef != null || formatable),
        records = records,
        error = error,
    )
}

/**
 * NTAG21x chips answer GET_VERSION (0x60). Byte 6 of the reply encodes the
 * storage size: 0x0F = NTAG213, 0x11 = NTAG215, 0x13 = NTAG216.
 */
private fun detectChip(tag: Tag): String {
    val mul = MifareUltralight.get(tag) ?: return tag.techList.firstOrNull()
        ?.substringAfterLast('.') ?: "Unknown"
    return try {
        mul.connect()
        val v = mul.transceive(byteArrayOf(0x60))
        if (v.size >= 8 && v[0] == 0.toByte() && v[1] == 0x04.toByte()) {
            when (v[6].toInt() and 0xFF) {
                0x0F -> "NTAG213 (144 B)"
                0x11 -> "NTAG215 (504 B)"
                0x13 -> "NTAG216 (888 B)"
                else -> "NXP NTAG (unknown size)"
            }
        } else "MIFARE Ultralight-compatible"
    } catch (e: Exception) {
        "MIFARE Ultralight-compatible"
    } finally {
        runCatching { mul.close() }
    }
}

fun parseRecord(r: NdefRecord): ParsedRecord {
    val type = r.type ?: ByteArray(0)
    return when {
        r.tnf == NdefRecord.TNF_WELL_KNOWN && type.contentEquals(NdefRecord.RTD_URI) -> {
            val uri = r.toUri()?.toString().orEmpty()
            val kind = when {
                uri.startsWith("tel:") -> "Phone"
                uri.startsWith("mailto:") -> "Email"
                uri.startsWith("sms:") -> "SMS"
                else -> "URL"
            }
            ParsedRecord(kind, uri)
        }

        r.tnf == NdefRecord.TNF_WELL_KNOWN && type.contentEquals(NdefRecord.RTD_TEXT) ->
            ParsedRecord("Text", decodeText(r.payload))

        r.tnf == NdefRecord.TNF_MIME_MEDIA -> {
            val mime = String(type, Charsets.US_ASCII).lowercase(Locale.ROOT)
            when {
                mime == "text/vcard" || mime == "text/x-vcard" ->
                    ParsedRecord("Contact", summarizeVCard(String(r.payload, Charsets.UTF_8)))
                mime.startsWith("text/") ->
                    ParsedRecord("MIME $mime", String(r.payload, Charsets.UTF_8))
                else -> ParsedRecord("MIME $mime", "${r.payload.size} bytes")
            }
        }

        else -> ParsedRecord("Other", "TNF ${r.tnf} · ${r.payload.size} bytes")
    }
}

private fun decodeText(payload: ByteArray): String {
    if (payload.isEmpty()) return ""
    val status = payload[0].toInt()
    val utf16 = status and 0x80 != 0
    val langLen = status and 0x3F
    val start = 1 + langLen
    if (start > payload.size) return ""
    return String(payload, start, payload.size - start, if (utf16) Charsets.UTF_16 else Charsets.UTF_8)
}

private fun summarizeVCard(vcard: String): String {
    val lines = vcard.lines()
    fun field(key: String) = lines
        .firstOrNull { it.substringBefore(':').substringBefore(';').equals(key, ignoreCase = true) }
        ?.substringAfter(':')?.trim()
    return listOfNotNull(
        field("FN")?.let { "Name: $it" },
        field("ORG")?.let { "Company: $it" },
        field("TEL")?.let { "Phone: $it" },
        field("EMAIL")?.let { "Email: $it" },
    ).joinToString("\n").ifEmpty { vcard.trim() }
}

private fun ByteArray.toHex(): String =
    joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

// ---------------------------------------------------------------- drafting

/** A record the user is composing before writing it to a tag. */
sealed interface DraftRecord {
    val label: String
    val preview: String
    fun toNdef(): NdefRecord

    data class Url(val url: String) : DraftRecord {
        override val label get() = "URL"
        override val preview get() = normalized()
        private fun normalized(): String {
            val u = url.trim()
            val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(u)
            return if (hasScheme) u else "https://$u"
        }
        override fun toNdef(): NdefRecord = NdefRecord.createUri(normalized())
    }

    data class Text(val text: String) : DraftRecord {
        override val label get() = "Text"
        override val preview get() = text
        override fun toNdef(): NdefRecord =
            NdefRecord.createTextRecord(Locale.getDefault().language.ifEmpty { "en" }, text)
    }

    data class Contact(
        val name: String,
        val phone: String,
        val email: String,
        val org: String,
    ) : DraftRecord {
        override val label get() = "Contact"
        override val preview get() = listOf(name, phone, email, org).filter { it.isNotBlank() }.joinToString(" · ")
        override fun toNdef(): NdefRecord {
            val first = name.trim().substringBeforeLast(' ', name.trim())
            val last = if (name.trim().contains(' ')) name.trim().substringAfterLast(' ') else ""
            val vcard = buildString {
                append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
                append("N:$last;$first;;;\r\n")
                append("FN:${name.trim()}\r\n")
                if (org.isNotBlank()) append("ORG:${org.trim()}\r\n")
                if (phone.isNotBlank()) append("TEL:${phone.trim()}\r\n")
                if (email.isNotBlank()) append("EMAIL:${email.trim()}\r\n")
                append("END:VCARD\r\n")
            }
            return NdefRecord.createMime("text/vcard", vcard.toByteArray(Charsets.UTF_8))
        }
    }
}

fun List<DraftRecord>.toMessageOrNull(): NdefMessage? =
    if (isEmpty()) null else NdefMessage(map { it.toNdef() }.toTypedArray())

fun emptyMessage(): NdefMessage =
    NdefMessage(NdefRecord(NdefRecord.TNF_EMPTY, null, null, null))

// ---------------------------------------------------------------- writing

/** Writes [message] to [tag]. Returns null on success, or a human-readable error. */
fun writeMessage(tag: Tag, message: NdefMessage): String? {
    return try {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            ndef.connect()
            try {
                if (!ndef.isWritable) return "This tag is read-only (locked)."
                val size = message.toByteArray().size
                if (size > ndef.maxSize) return "Too big: $size bytes, but this tag holds ${ndef.maxSize}."
                ndef.writeNdefMessage(message)
            } finally {
                runCatching { ndef.close() }
            }
            null
        } else {
            val formatable = NdefFormatable.get(tag) ?: return "This tag doesn't support NDEF."
            formatable.connect()
            try {
                formatable.format(message)
            } finally {
                runCatching { formatable.close() }
            }
            null
        }
    } catch (e: TagLostException) {
        "Tag moved away. Hold it steady and try again."
    } catch (e: Exception) {
        "Write failed: ${e.message ?: e.javaClass.simpleName}"
    }
}
