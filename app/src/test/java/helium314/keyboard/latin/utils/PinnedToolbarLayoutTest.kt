// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View.MeasureSpec
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PinnedToolbarLayoutTest {

    @Test
    fun applyPinnedToolbarKeyLayout_usesEqualWeightsAndClearsMinWidth() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val button = ImageButton(context)
        button.minimumWidth = context.resources.getDimensionPixelSize(R.dimen.config_suggestion_min_width)
        button.setPadding(20, 0, 20, 0)

        applyPinnedToolbarKeyLayout(button)

        val params = button.layoutParams as LinearLayout.LayoutParams
        assertEquals(0, params.width)
        assertEquals(LinearLayout.LayoutParams.MATCH_PARENT, params.height)
        assertEquals(1f, params.weight)
        assertEquals(0, button.minimumWidth)
        assertEquals(0, button.paddingStart)
        assertEquals(0, button.paddingEnd)
    }

    @Test
    fun elevenPinnedKeys_fitEvenlyOnNarrowPhoneWithoutOverflow() {
        assertElevenKeysFitEvenly(320)
        assertElevenKeysFitEvenly(360)
        assertElevenKeysFitEvenly(411)
    }

    private fun assertElevenKeysFitEvenly(widthDp: Int) {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(),
            R.style.KeyboardTheme_HoloBase
        )
        val container = LayoutInflater.from(context)
            .inflate(R.layout.secondary_toolbar, null) as LinearLayout
        val pinnedKeys = container.findViewById<LinearLayout>(R.id.pinned_keys)
        repeat(11) {
            val button = ImageButton(context)
            applyPinnedToolbarKeyLayout(button)
            pinnedKeys.addView(button)
        }

        val density = context.resources.displayMetrics.density
        val widthPx = (widthDp * density).toInt()
        val height = context.resources.getDimensionPixelSize(R.dimen.config_secondary_toolbar_height)
        container.measure(
            MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        container.layout(0, 0, container.measuredWidth, container.measuredHeight)

        assertEquals(widthPx, container.width, "container width at ${widthDp}dp")
        assertEquals(11, pinnedKeys.childCount)

        val first = pinnedKeys.getChildAt(0)
        val last = pinnedKeys.getChildAt(pinnedKeys.childCount - 1)
        val firstEdge = first.left + pinnedKeys.left
        val lastEdge = container.width - (last.right + pinnedKeys.left)
        assertEquals(container.paddingStart, firstEdge, "first key should use the strip start inset at ${widthDp}dp")
        assertEquals(container.paddingEnd, lastEdge, "last key should use the strip end inset at ${widthDp}dp")
        assertTrue(container.paddingStart > 0)
        assertTrue(container.paddingEnd > 0)
        assertTrue(container.paddingStart <= (2 * density).toInt() + 1)
        assertTrue(container.paddingEnd <= (2 * density).toInt() + 1)

        val widths = (0 until pinnedKeys.childCount).map { pinnedKeys.getChildAt(it).width }
        assertTrue(widths.max() - widths.min() <= 1, "pinned keys should share width evenly at ${widthDp}dp, got $widths")
        for (i in 0 until pinnedKeys.childCount) {
            val child = pinnedKeys.getChildAt(i)
            assertTrue(child.width > 0, "key $i should have width at ${widthDp}dp")
            assertTrue(child.left >= 0, "key $i should not overflow start at ${widthDp}dp")
            assertTrue(child.right <= pinnedKeys.width, "key $i should not overflow end at ${widthDp}dp")
        }
    }
}
