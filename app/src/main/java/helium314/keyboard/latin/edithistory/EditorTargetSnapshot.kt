// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.edithistory

import android.view.inputmethod.EditorInfo
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Identifies a host-app text field for edit-history storage and fullapp draft matching.
 */
data class EditorTargetSnapshot(
    val packageName: String,
    val fieldId: Int,
    val fieldName: String,
    val inputType: Int,
    val imeOptions: Int,
    val privateImeOptions: String,
) {
    val storageKey: String
        get() = sha256("$packageName|$fieldId|$fieldName|$inputType|$imeOptions|$privateImeOptions")

    val hasStrongIdentity: Boolean
        get() = fieldId != 0 || fieldName.isNotBlank()

    fun debugSummary(): String = buildString {
        append("pkg=").append(packageName)
        if (fieldId != 0) append(", fieldId=").append(fieldId)
        if (fieldName.isNotBlank()) append(", fieldName=").append(fieldName)
        append(", inputType=0x").append(inputType.toUInt().toString(16))
        append(", imeOptions=0x").append(imeOptions.toUInt().toString(16))
        if (privateImeOptions.isNotBlank()) append(", privateImeOptions=present")
    }

    fun matchScore(editorInfo: EditorInfo): Int {
        if (packageName != editorInfo.packageName.orEmpty()) {
            return Int.MIN_VALUE
        }
        val currentFieldName = editorInfo.fieldName?.toString().orEmpty()
        val currentPrivateImeOptions = editorInfo.privateImeOptions.orEmpty()
        var score = 100
        if (fieldId != 0 && editorInfo.fieldId != 0) {
            if (fieldId != editorInfo.fieldId) {
                return Int.MIN_VALUE
            }
            score += 40
        } else if (fieldId != 0 || editorInfo.fieldId != 0) {
            score -= 5
        }
        if (fieldName.isNotBlank() && currentFieldName.isNotBlank()) {
            if (fieldName != currentFieldName) {
                return Int.MIN_VALUE
            }
            score += 30
        } else if (fieldName.isNotBlank() || currentFieldName.isNotBlank()) {
            score -= 3
        }
        if (inputType == editorInfo.inputType) {
            score += 10
        } else if (!hasStrongIdentity) {
            return Int.MIN_VALUE
        }
        if (privateImeOptions.isNotBlank() && currentPrivateImeOptions.isNotBlank()) {
            if (privateImeOptions == currentPrivateImeOptions) {
                score += 5
            } else if (!hasStrongIdentity) {
                return Int.MIN_VALUE
            }
        }
        if (imeOptions == editorInfo.imeOptions) {
            score += 3
        }
        return score
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put(JSON_PACKAGE_NAME, packageName)
        put(JSON_FIELD_ID, fieldId)
        put(JSON_FIELD_NAME, fieldName)
        put(JSON_INPUT_TYPE, inputType)
        put(JSON_IME_OPTIONS, imeOptions)
        put(JSON_PRIVATE_IME_OPTIONS, privateImeOptions)
    }

    companion object {
        const val JSON_PACKAGE_NAME = "package_name"
        const val JSON_FIELD_ID = "field_id"
        const val JSON_FIELD_NAME = "field_name"
        const val JSON_INPUT_TYPE = "input_type"
        const val JSON_IME_OPTIONS = "ime_options"
        const val JSON_PRIVATE_IME_OPTIONS = "private_ime_options"

        fun createFrom(editorInfo: EditorInfo?): EditorTargetSnapshot? {
            val packageName = editorInfo?.packageName?.takeIf { it.isNotBlank() } ?: return null
            return EditorTargetSnapshot(
                packageName = packageName,
                fieldId = editorInfo.fieldId,
                fieldName = editorInfo.fieldName?.toString().orEmpty(),
                inputType = editorInfo.inputType,
                imeOptions = editorInfo.imeOptions,
                privateImeOptions = editorInfo.privateImeOptions.orEmpty()
            )
        }

        fun fromJson(json: JSONObject): EditorTargetSnapshot = EditorTargetSnapshot(
            packageName = json.getString(JSON_PACKAGE_NAME),
            fieldId = json.optInt(JSON_FIELD_ID, 0),
            fieldName = json.optString(JSON_FIELD_NAME, ""),
            inputType = json.optInt(JSON_INPUT_TYPE, 0),
            imeOptions = json.optInt(JSON_IME_OPTIONS, 0),
            privateImeOptions = json.optString(JSON_PRIVATE_IME_OPTIONS, "")
        )

        private fun sha256(raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}
