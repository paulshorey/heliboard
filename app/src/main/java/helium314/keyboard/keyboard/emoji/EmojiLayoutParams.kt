/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.keyboard.emoji

import android.content.res.Resources
import android.view.View
import android.widget.LinearLayout
import androidx.viewpager2.widget.ViewPager2
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils

internal class EmojiLayoutParams(res: Resources) {
    val emojiKeyboardHeight: Int
    private val emojiCategoryPageIdViewHeight: Int

    init {
        val sv = Settings.getValues()
        val panelHeight = ResourceUtils.getKeyboardLayoutHeightForPanel(res, sv)
        emojiCategoryPageIdViewHeight = res.getDimension(R.dimen.config_emoji_category_page_id_height).toInt()
        val occupiedBottomRow = ResourceUtils.getPanelFunctionalRowOccupiedHeight(res, sv)
        emojiKeyboardHeight = (panelHeight - occupiedBottomRow - emojiCategoryPageIdViewHeight).coerceAtLeast(0)
    }

    fun setEmojiListProperties(vp: ViewPager2) {
        val lp = vp.layoutParams as LinearLayout.LayoutParams
        lp.height = 0
        lp.weight = 1f
        lp.bottomMargin = 0
        vp.layoutParams = lp
    }

    fun setCategoryPageIdViewProperties(v: View) {
        val lp = v.layoutParams as LinearLayout.LayoutParams
        lp.height = emojiCategoryPageIdViewHeight
        v.layoutParams = lp
    }
}
