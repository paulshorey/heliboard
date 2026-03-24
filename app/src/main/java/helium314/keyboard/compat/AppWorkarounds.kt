// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.compat

import android.text.InputType
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.utils.InputTypeUtils

/**
 * Per-app workarounds that adjust EditorInfo flags before the keyboard uses them.
 *
 * The most important workaround is for Firefox and its forks, which do not set
 * [InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT] on their web text fields. Without this flag,
 * the keyboard cannot detect that it is typing into a web field and will use the standard
 * span-rich input path, which causes invisible characters and cursor issues in contentEditable
 * divs. This class patches the inputType for known Firefox packages so that
 * [helium314.keyboard.latin.InputAttributes.isWebEditTextField] returns true.
 *
 * Chrome, Chromium-based browsers (Edge, Brave, Opera, Samsung Internet), and Android's stock
 * WebView all set TYPE_TEXT_VARIATION_WEB_EDIT_TEXT correctly and do not need patching.
 *
 * If a new browser is found that omits the flag, add its package name to [firefoxPackages]
 * (or create a new set if the heuristic differs).
 */
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

    /**
     * Firefox omits TYPE_TEXT_VARIATION_WEB_EDIT_TEXT and TYPE_TEXT_FLAG_NO_SUGGESTIONS.
     * We add them based on heuristics: text class, no variation set, multiline IME flag present.
     */
    private fun adjustFirefoxInputType(inputType: Int): Int {
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
