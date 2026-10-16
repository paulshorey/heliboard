// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import androidx.core.content.edit
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.shouldShowPinnedToolbarRow
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowLocaleManagerCompat::class,
    ShadowInputMethodManager2::class,
])
class ShowPinnedToolbarSettingTest {
    private lateinit var latinIME: LatinIME

    @BeforeTest
    fun setUp() {
        latinIME = Robolectric.setupService(LatinIME::class.java)
        ShadowLog.setupLogging()
        ShadowLog.stream = System.out
        latinIME.prefs().edit { remove(Settings.PREF_SHOW_PINNED_TOOLBAR) }
    }

    @Test
    fun pinnedToolbarRowIsShownByDefault() {
        assertTrue(Defaults.PREF_SHOW_PINNED_TOOLBAR)
        assertTrue(Settings.getValues().mShowPinnedToolbar)
    }

    @Test
    fun hidingPinnedToolbarReloadsSettingsValues() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_SHOW_PINNED_TOOLBAR, false) }
        assertFalse(Settings.getValues().mShowPinnedToolbar)

        latinIME.prefs().edit { putBoolean(Settings.PREF_SHOW_PINNED_TOOLBAR, true) }
        assertTrue(Settings.getValues().mShowPinnedToolbar)
    }

    @Test
    fun shouldShowPinnedToolbarRow_requiresUserSettingAndPinnedKeys() {
        assertTrue(
            shouldShowPinnedToolbarRow(
                showPinnedToolbar = true,
                suggestionStripHidden = false,
                deviceLocked = false,
                hasPinnedKeys = true,
            )
        )
        assertFalse(
            shouldShowPinnedToolbarRow(
                showPinnedToolbar = false,
                suggestionStripHidden = false,
                deviceLocked = false,
                hasPinnedKeys = true,
            )
        )
        assertFalse(
            shouldShowPinnedToolbarRow(
                showPinnedToolbar = true,
                suggestionStripHidden = true,
                deviceLocked = false,
                hasPinnedKeys = true,
            )
        )
        assertFalse(
            shouldShowPinnedToolbarRow(
                showPinnedToolbar = true,
                suggestionStripHidden = false,
                deviceLocked = true,
                hasPinnedKeys = true,
            )
        )
        assertFalse(
            shouldShowPinnedToolbarRow(
                showPinnedToolbar = true,
                suggestionStripHidden = false,
                deviceLocked = false,
                hasPinnedKeys = false,
            )
        )
    }
}
