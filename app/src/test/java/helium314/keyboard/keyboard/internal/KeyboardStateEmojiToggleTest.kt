// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.internal

import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyboardStateEmojiToggleTest {

    @Test
    fun emojiKey_opensPaletteThenReturnsToAlphabet() {
        val actions = RecordingSwitchActions()
        val state = KeyboardState(actions)
        state.onLoadKeyboard(0, null, false)
        actions.clear()

        state.onEvent(emojiEvent(), 0, null)
        assertEquals(listOf("emoji"), actions.keyboardActions())

        state.onEvent(emojiEvent(), 0, null)
        assertEquals(listOf("emoji", "alphabet"), actions.keyboardActions())
    }

    @Test
    fun emojiKey_fromSymbols_opensPaletteThenReturnsToAlphabet() {
        val actions = RecordingSwitchActions()
        val state = KeyboardState(actions)
        state.onLoadKeyboard(0, null, false)
        state.onEvent(softwareKeyEvent(KeyCode.SYMBOL), 0, null)
        actions.clear()

        state.onEvent(emojiEvent(), 0, null)
        assertEquals(listOf("emoji"), actions.keyboardActions())

        state.onEvent(emojiEvent(), 0, null)
        assertEquals(listOf("emoji", "alphabet"), actions.keyboardActions())
    }

    @Test
    fun emojiKey_opensPaletteAfterPhysicalShortcutHidesItWithoutUpdatingMode() {
        val actions = RecordingSwitchActions()
        val state = KeyboardState(actions)
        state.onLoadKeyboard(0, null, false)
        state.onEvent(emojiEvent(), 0, null)
        // Physical symbols shortcut changes the UI without going through KeyboardState.
        actions.showingEmoji = false
        actions.clear()

        state.onEvent(emojiEvent(), 0, null)
        assertEquals(listOf("emoji"), actions.keyboardActions())
    }

    @Test
    fun emojiKey_returnsToAlphabetWhenPhysicalShortcutShowsPaletteWithoutUpdatingMode() {
        val actions = RecordingSwitchActions()
        val state = KeyboardState(actions)
        state.onLoadKeyboard(0, null, false)
        // Physical emoji shortcut shows the palette without going through KeyboardState.
        actions.showingEmoji = true
        actions.clear()

        state.onEvent(emojiEvent(), 0, null)
        assertEquals(listOf("alphabet"), actions.keyboardActions())
    }

    private fun emojiEvent() = softwareKeyEvent(KeyCode.EMOJI)

    private fun softwareKeyEvent(keyCode: Int) =
        Event.createSoftwareKeypressEvent(keyCode, 0, 0, 0, false)

    private class RecordingSwitchActions : KeyboardState.SwitchActions {
        private val actions = mutableListOf<String>()
        var showingEmoji = false

        fun clear() = actions.clear()

        fun keyboardActions() = actions.filter { it == "alphabet" || it == "emoji" || it == "symbols" }

        override fun setAlphabetKeyboard() {
            showingEmoji = false
            actions.add("alphabet")
        }
        override fun setAlphabetManualShiftedKeyboard() { actions.add("alphabetManual") }
        override fun setAlphabetAutomaticShiftedKeyboard() { actions.add("alphabetAutomatic") }
        override fun setAlphabetShiftLockedKeyboard() { actions.add("alphabetShiftLocked") }
        override fun setAlphabetShiftLockShiftedKeyboard() { actions.add("alphabetShiftLockShifted") }
        override fun setEmojiKeyboard() {
            showingEmoji = true
            actions.add("emoji")
        }
        override fun setClipboardKeyboard() {
            showingEmoji = false
            actions.add("clipboard")
        }
        override fun setNumpadKeyboard() { actions.add("numpad") }
        override fun toggleNumpad(
            withSliding: Boolean,
            autoCapsFlags: Int,
            recapitalizeMode: helium314.keyboard.latin.utils.RecapitalizeMode?,
            forceReturnToAlpha: Boolean
        ) {
            actions.add("toggleNumpad")
        }
        override fun setSymbolsKeyboard() {
            showingEmoji = false
            actions.add("symbols")
        }
        override fun setSymbolsShiftedKeyboard() {
            showingEmoji = false
            actions.add("symbolsShifted")
        }
        override fun requestUpdatingShiftState(
            autoCapsFlags: Int,
            recapitalizeMode: helium314.keyboard.latin.utils.RecapitalizeMode?
        ) {
            actions.add("updateShift")
        }
        override fun startDoubleTapShiftKeyTimer() {}
        override val isInDoubleTapShiftKeyTimeout = false
        override fun cancelDoubleTapShiftKeyTimer() {}
        override val isShowingEmojiKeyboard get() = showingEmoji
        override fun setOneHandedModeEnabled(enabled: Boolean) {}
        override fun switchOneHandedMode() {}
    }
}
