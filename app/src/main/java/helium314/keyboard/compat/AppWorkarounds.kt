// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.compat

import android.text.InputType
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.utils.InputTypeUtils

object AppWorkarounds {

    private val firefoxPackages = setOf(
        "org.mozilla.fennec_fdroid", "org.mozilla.fenix", "org.mozilla.firefox_beta", "org.mozilla.focus",
        "org.mozilla.klar", "org.mozilla.firefox", "org.ironfoxoss.ironfox", "net.waterfox.android.release",
        "io.github.forkmaintainers.iceraven", "com.zen.web.tools.browser"
    )

    fun adjustInputType(inputType: Int, packageName: String?): Int {
        if (packageName != null && firefoxPackages.contains(packageName)) {
            return adjustFirefoxInputType(inputType)
        }
        return inputType
    }

    private fun adjustFirefoxInputType(inputType: Int): Int {
        // Firefox and forks don't set these flags, so we want to force them for most text fields on websites.
        // Missing TYPE_TEXT_VARIATION_WEB_EDIT_TEXT is strange, considering all text fields on web pages should set it.
        // Missing TYPE_TEXT_FLAG_NO_SUGGESTIONS causes JS to interfere with composing region.
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return inputType
        if (inputType and InputType.TYPE_MASK_VARIATION != 0) return inputType
        if (inputType and InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE == 0) return inputType
        if (inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT == 0) return inputType or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
        return inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
    }

    fun adjustImeOptions(imeOptions: Int, packageName: String?): Int {
        return when (packageName) {
            "com.google.android.apps.nexuslauncher" -> if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) imeOptions - EditorInfo.IME_FLAG_NO_ENTER_ACTION else imeOptions
            else -> imeOptions
        }
    }
}
