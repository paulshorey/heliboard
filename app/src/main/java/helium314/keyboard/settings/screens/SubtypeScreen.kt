package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_ALL
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_MAIN
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_MORE
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_NORMAL
import helium314.keyboard.keyboard.internal.keyboard_parser.hasLocalizedNumberRow
import helium314.keyboard.keyboard.internal.keyboard_parser.morePopupKeysResId
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.common.Links
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutType.Companion.displayNameId
import helium314.keyboard.latin.utils.LayoutUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ScriptUtils
import helium314.keyboard.latin.utils.ScriptUtils.script
import helium314.keyboard.latin.utils.SubtypeLocaleUtils
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.getDictionaryLocales
import helium314.keyboard.latin.utils.getSecondaryLocales
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.htmlToAnnotated
import helium314.keyboard.latin.utils.mainLayoutName
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.withHtmlLink
import helium314.keyboard.settings.ActionRow
import helium314.keyboard.settings.DefaultButton
import helium314.keyboard.settings.DeleteButton
import helium314.keyboard.settings.DropDownField
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.Theme
import helium314.keyboard.settings.WithSmallTitle
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.LayoutEditDialog
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.dialogs.MultiListPickerDialog
import helium314.keyboard.settings.dialogs.ReorderDialog
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.layoutFilePicker
import helium314.keyboard.settings.layoutIntent
import helium314.keyboard.settings.previewDark
import java.util.Locale

@Composable
fun SubtypeScreen(
    initialSubtype: SettingsSubtype,
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    var currentSubtypeString by rememberSaveable { mutableStateOf(initialSubtype.toPref()) }
    val currentSubtype = currentSubtypeString.toSettingsSubtype()
    fun setCurrentSubtype(subtype: SettingsSubtype) {
        SubtypeUtilsAdditional.changeAdditionalSubtype(currentSubtype, subtype, ctx)
        currentSubtypeString = subtype.toPref()
    }
    LaunchedEffect(currentSubtypeString) {
        if (ScriptUtils.scriptSupportsUppercase(currentSubtype.locale)) return@LaunchedEffect
        // update the noShiftKey extra value
        val mainLayout = currentSubtype.mainLayoutName()
        val noShiftKey = if (mainLayout != null && LayoutUtilsCustom.isCustomLayout(mainLayout)) {
            // determine from layout
            val content = LayoutUtilsCustom.getLayoutFile(mainLayout, LayoutType.MAIN, ctx).readText()
            !content.contains("\"shift_state_selector\"")
        } else {
            // determine from subtype with same layout
            SubtypeSettings.getResourceSubtypesForLocale(currentSubtype.locale)
                .firstOrNull { it.mainLayoutName() == mainLayout }
                ?.containsExtraValueKey(ExtraValue.NO_SHIFT_KEY) ?: false
        }
        if (!noShiftKey && currentSubtype.hasExtraValueOf(ExtraValue.NO_SHIFT_KEY))
            setCurrentSubtype(currentSubtype.without(ExtraValue.NO_SHIFT_KEY))
        else if (noShiftKey && !currentSubtype.hasExtraValueOf(ExtraValue.NO_SHIFT_KEY))
            setCurrentSubtype(currentSubtype.with(ExtraValue.NO_SHIFT_KEY))
    }

    val availableLocalesForScript = getAvailableSecondaryLocales(ctx, currentSubtype.locale).sortedBy { it.toLanguageTag() }
    var showSecondaryLocaleDialog by remember { mutableStateOf(false) }
    var showKeyOrderDialog by remember { mutableStateOf(false) }
    var showHintOrderDialog by remember { mutableStateOf(false) }
    var showMorePopupsDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    SearchScreen(
        onClickBack = onClickBack,
        icon = { if (currentSubtype.isAdditionalSubtype(prefs)) DeleteButton {
            SubtypeUtilsAdditional.removeAdditionalSubtype(ctx, currentSubtype.toAdditionalSubtype())
            SubtypeSettings.removeEnabledSubtype(ctx, currentSubtype.toAdditionalSubtype())
            onClickBack()
        } },
        title = { Text(currentSubtype.toAdditionalSubtype().displayName()) },
        itemContent = { },
        filteredItems = { emptyList<String>() }
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
        ) { innerPadding ->
            Column(
                modifier = Modifier.verticalScroll(scrollState).padding(horizontal = 12.dp)
                    .then(Modifier.padding(innerPadding)),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LayoutSlotEditor(
                    slotType = LayoutType.MAIN,
                    currentSubtype = currentSubtype,
                    setCurrentSubtype = { setCurrentSubtype(it) },
                )
                if (availableLocalesForScript.size > 1) {
                    WithSmallTitle(stringResource(R.string.secondary_locale)) {
                        ActionRow(onClick = { showSecondaryLocaleDialog = true }) {
                            val text = getSecondaryLocales(currentSubtype.extraValues).joinToString(", ") {
                                it.localizedDisplayName(ctx.resources)
                            }.ifEmpty { stringResource(R.string.action_none) }
                            Text(text, modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp)
                            )
                        }
                    }
                }
                WithSmallTitle(stringResource(R.string.popup_order_and_hint_source)) {
                    ActionRow(onClick = { showKeyOrderDialog = true }) {
                        Text(stringResource(R.string.popup_order),
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp)
                        )
                        DefaultButton(currentSubtype.getExtraValueOf(ExtraValue.POPUP_ORDER) == null) {
                            setCurrentSubtype(currentSubtype.without(ExtraValue.POPUP_ORDER))
                        }
                    }
                    ActionRow(onClick = { showHintOrderDialog = true }) {
                        Text(stringResource(R.string.hint_source),
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp)
                        )
                        DefaultButton(currentSubtype.getExtraValueOf(ExtraValue.HINT_ORDER) == null) {
                            setCurrentSubtype(currentSubtype.without(ExtraValue.HINT_ORDER))
                        }
                    }
                }
                if (currentSubtype.locale.script() == ScriptUtils.SCRIPT_LATIN) {
                    WithSmallTitle(stringResource(R.string.show_popup_keys_title)) {
                        val explicitValue = currentSubtype.getExtraValueOf(ExtraValue.MORE_POPUPS)
                        val value = explicitValue ?: prefs.getString(
                            Settings.PREF_MORE_POPUP_KEYS,
                            Defaults.PREF_MORE_POPUP_KEYS
                        )!!
                        ActionRow(onClick = { showMorePopupsDialog = true }) {
                            Text(stringResource(morePopupKeysResId(value)),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 10.dp)
                            )
                            DefaultButton(explicitValue == null) {
                                setCurrentSubtype(currentSubtype.without(ExtraValue.MORE_POPUPS))
                            }
                        }
                    }
                }
                if (hasLocalizedNumberRow(currentSubtype.locale, ctx)) {
                    val checked = currentSubtype.getExtraValueOf(ExtraValue.LOCALIZED_NUMBER_ROW)?.toBoolean()
                    WithSmallTitle(stringResource(R.string.number_row)) {
                        ActionRow {
                            Text(stringResource(R.string.localized_number_row),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 10.dp)
                            )
                            Switch(
                                checked = checked ?: prefs.getBoolean(
                                    Settings.PREF_LOCALIZED_NUMBER_ROW,
                                    Defaults.PREF_LOCALIZED_NUMBER_ROW
                                ),
                                onCheckedChange = {
                                    setCurrentSubtype(currentSubtype.with(ExtraValue.LOCALIZED_NUMBER_ROW, it.toString()))
                                }
                            )
                            DefaultButton(checked == null) {
                                setCurrentSubtype(currentSubtype.without(ExtraValue.LOCALIZED_NUMBER_ROW))
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text(
                    stringResource(R.string.settings_screen_secondary_layouts),
                    style = MaterialTheme.typography.titleMedium
                )
                LayoutType.entries.forEach { type ->
                    if (type == LayoutType.MAIN) return@forEach
                    LayoutSlotEditor(
                        slotType = type,
                        currentSubtype = currentSubtype,
                        setCurrentSubtype = { setCurrentSubtype(it) },
                    )
                }
            }
        }
        if (showSecondaryLocaleDialog)
            MultiListPickerDialog(
                onDismissRequest = { showSecondaryLocaleDialog = false },
                onConfirmed = { locales ->
                    val newValue = locales.joinToString(Separators.KV) { it.toLanguageTag() }
                    setCurrentSubtype(
                        if (newValue.isEmpty()) currentSubtype.without(ExtraValue.SECONDARY_LOCALES)
                        else currentSubtype.with(ExtraValue.SECONDARY_LOCALES, newValue)
                    )
                },
                title = { Text(stringResource(R.string.locales_with_dict)) },
                items = availableLocalesForScript,
                initialSelection = currentSubtype.getExtraValueOf(ExtraValue.SECONDARY_LOCALES)
                    ?.split(Separators.KV)?.map { it.constructLocale() }.orEmpty(),
                getItemName = { it.localizedDisplayName(ctx.resources) }
            )
        if (showKeyOrderDialog) {
            val setting = currentSubtype.getExtraValueOf(ExtraValue.POPUP_ORDER)
            PopupOrderDialog(
                onDismissRequest = { showKeyOrderDialog = false },
                initialValue = setting ?: prefs.getString(
                    Settings.PREF_POPUP_KEYS_ORDER,
                    Defaults.PREF_POPUP_KEYS_ORDER
                )!!,
                title = stringResource(R.string.popup_order),
                showDefault = setting != null,
                onConfirmed = {
                    setCurrentSubtype(
                        if (it == null) currentSubtype.without(ExtraValue.POPUP_ORDER)
                        else currentSubtype.with(ExtraValue.POPUP_ORDER, it)
                    )
                }
            )
        }
        if (showHintOrderDialog) {
            val setting = currentSubtype.getExtraValueOf(ExtraValue.HINT_ORDER)
            PopupOrderDialog(
                onDismissRequest = { showHintOrderDialog = false },
                initialValue = setting ?: prefs.getString(
                    Settings.PREF_POPUP_KEYS_LABELS_ORDER,
                    Defaults.PREF_POPUP_KEYS_LABELS_ORDER
                )!!,
                title = stringResource(R.string.hint_source),
                showDefault = setting != null,
                onConfirmed = {
                    setCurrentSubtype(
                        if (it == null) currentSubtype.without(ExtraValue.HINT_ORDER)
                        else currentSubtype.with(ExtraValue.HINT_ORDER, it)
                    )
                }
            )
        }
        if (showMorePopupsDialog) {
            val items = listOf(POPUP_KEYS_NORMAL, POPUP_KEYS_MAIN, POPUP_KEYS_MORE, POPUP_KEYS_ALL)
            val explicitValue = currentSubtype.getExtraValueOf(ExtraValue.MORE_POPUPS)
            val value = explicitValue ?: prefs.getString(Settings.PREF_MORE_POPUP_KEYS, Defaults.PREF_MORE_POPUP_KEYS)
            ListPickerDialog(
                onDismissRequest = { showMorePopupsDialog = false },
                items = items,
                getItemName = { stringResource(morePopupKeysResId(it)) },
                selectedItem = value,
                onItemSelected = { setCurrentSubtype(currentSubtype.with(ExtraValue.MORE_POPUPS, it)) }
            )
        }
    }
}

@Composable
private fun LayoutSlotEditor(
    slotType: LayoutType,
    currentSubtype: SettingsSubtype,
    setCurrentSubtype: (SettingsSubtype) -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val isMain = slotType == LayoutType.MAIN
    val locale = currentSubtype.locale

    val builtInLayouts = if (isMain) {
        LayoutUtils.getAvailableLayouts(LayoutType.MAIN, ctx, locale).toList()
    } else {
        LayoutUtils.getAvailableLayouts(slotType, ctx).toList()
    }
    val customLayouts = if (isMain) {
        LayoutUtilsCustom.getLayoutFiles(LayoutType.MAIN, ctx, locale).map { it.name }
    } else {
        LayoutUtilsCustom.getLayoutFiles(slotType, ctx).map { it.name }
    }

    val selectedLayout = if (isMain) {
        currentSubtype.mainLayoutName() ?: SubtypeLocaleUtils.QWERTY
    } else {
        currentSubtype.layoutName(slotType) ?: Settings.readDefaultLayoutName(slotType, prefs)
    }

    var showAddLayoutDialog by remember { mutableStateOf(false) }
    var showLayoutEditDialog: Pair<String, String?>? by remember { mutableStateOf(null) }
    val layoutPicker = layoutFilePicker { content, name ->
        showLayoutEditDialog = (name ?: "new layout") to content
    }

    WithSmallTitle(
        if (isMain) stringResource(R.string.keyboard_layout_set)
        else stringResource(slotType.displayNameId)
    ) {
        DropDownField(
            items = builtInLayouts + customLayouts,
            selectedItem = selectedLayout,
            onSelected = { layout ->
                if (isMain) {
                    if (layout == SubtypeLocaleUtils.QWERTY
                        && SubtypeSettings.getResourceSubtypesForLocale(locale).any { it.mainLayoutName() == null })
                        setCurrentSubtype(currentSubtype.withoutLayout(LayoutType.MAIN))
                    else setCurrentSubtype(currentSubtype.withLayout(LayoutType.MAIN, layout))
                } else {
                    setCurrentSubtype(currentSubtype.withLayout(slotType, layout))
                }
            },
            extraButton = {
                IconButton({ showAddLayoutDialog = true })
                { Icon(painterResource(R.drawable.ic_plus), stringResource(R.string.button_title_add_custom_layout)) }
                if (!isMain) {
                    val explicitLayout = currentSubtype.layoutName(slotType)
                    DefaultButton(explicitLayout == null) {
                        setCurrentSubtype(currentSubtype.withoutLayout(slotType))
                    }
                }
            }
        ) {
            var showLayoutDeleteDialog by remember { mutableStateOf(false) }
            val isCustom = LayoutUtilsCustom.isCustomLayout(it)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.widthIn(min = 200.dp).fillMaxWidth()
            ) {
                val displayName = if (isMain) {
                    SubtypeLocaleUtils.getLayoutDisplayNameInSystemLocale(it, locale)
                } else {
                    if (isCustom) LayoutUtilsCustom.getDisplayName(it)
                    else it.getStringResourceOrName("layout_", ctx)
                }
                Text(displayName)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCustom) {
                        IconButton({ showLayoutEditDialog = it to null }) {
                            Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.edit_layout))
                        }
                        IconButton({ showLayoutDeleteDialog = true }) {
                            Icon(painterResource(R.drawable.ic_bin), stringResource(R.string.delete))
                        }
                    } else {
                        IconButton({
                            val content = if (isMain)
                                LayoutUtils.getContentWithPlus(it, locale, ctx)
                            else
                                LayoutUtils.getContent(slotType, it, ctx)
                            showLayoutEditDialog = "$it-copy" to content
                        }) {
                            Icon(painterResource(R.drawable.ic_fork_layout), stringResource(R.string.fork_layout))
                        }
                    }
                }
            }
            if (showLayoutDeleteDialog) {
                val others = if (isMain) {
                    SubtypeSettings.getAdditionalSubtypes().filter { st -> st.mainLayoutName() == it }
                        .any { st -> st.toSettingsSubtype() != currentSubtype }
                } else {
                    SubtypeSettings.getAdditionalSubtypes()
                        .any { st -> st.toSettingsSubtype().layoutName(slotType) == it && st.toSettingsSubtype() != currentSubtype }
                }
                ConfirmationDialog(
                    onDismissRequest = { showLayoutDeleteDialog = false },
                    confirmButtonText = stringResource(R.string.delete),
                    title = { Text(stringResource(R.string.delete_layout, LayoutUtilsCustom.getDisplayName(it))) },
                    content = { if (others) Text(stringResource(R.string.layout_in_use)) },
                    onConfirmed = {
                        val currentSlotLayout = if (isMain) currentSubtype.mainLayoutName() else currentSubtype.layoutName(slotType)
                        if (it == currentSlotLayout) {
                            if (isMain) {
                                val defaultLayout = SubtypeSettings.getResourceSubtypesForLocale(locale).firstOrNull()?.mainLayoutName()
                                val newSubtype = if (defaultLayout == null) currentSubtype.withoutLayout(LayoutType.MAIN)
                                    else currentSubtype.withLayout(LayoutType.MAIN, defaultLayout)
                                setCurrentSubtype(newSubtype)
                            } else {
                                setCurrentSubtype(currentSubtype.withoutLayout(slotType))
                            }
                        }
                        LayoutUtilsCustom.deleteLayout(it, slotType, ctx)
                        (ctx.getActivity() as? SettingsActivity)?.prefChanged()
                    }
                )
            }
        }
        if (showLayoutEditDialog != null) {
            val layoutName = showLayoutEditDialog!!.first
            val startContent = showLayoutEditDialog?.second
                ?: if (isMain && layoutName in builtInLayouts) LayoutUtils.getContentWithPlus(layoutName, locale, ctx)
                else if (!isMain && layoutName in builtInLayouts) LayoutUtils.getContent(slotType, layoutName, ctx)
                else null
            val isForkedFromPlus = !LayoutUtilsCustom.isCustomLayout(layoutName)
                    && layoutName.removeSuffix("-copy").endsWith("+")
            LayoutEditDialog(
                onDismissRequest = { showLayoutEditDialog = null },
                layoutType = slotType,
                initialLayoutName = layoutName,
                startContent = startContent,
                locale = if (isMain) locale else null,
                isNameValid = { it !in customLayouts },
                isForkFromPlusLayout = isForkedFromPlus,
                onEdited = {
                    if (layoutName !in customLayouts || (layoutName != it && layoutName == selectedLayout))
                        setCurrentSubtype(currentSubtype.withLayout(slotType, it))
                }
            )
        }
        if (showAddLayoutDialog) {
            val wikiLink = stringResource(R.string.dictionary_link_text).withHtmlLink(Links.LAYOUT_WIKI_URL)
            val layoutText = stringResource(R.string.message_add_custom_layout, wikiLink).htmlToAnnotated()
            val discussionLink = stringResource(R.string.discussion_section_link).withHtmlLink(Links.CUSTOM_LAYOUTS)
            val discussionSectionText = stringResource(R.string.get_layouts_message, discussionLink).htmlToAnnotated()
            val annotated = layoutText + AnnotatedString("\n") + discussionSectionText

            ConfirmationDialog(
                onDismissRequest = { showAddLayoutDialog = false },
                title = { Text(stringResource(R.string.button_title_add_custom_layout)) },
                content = { Text(annotated) },
                onConfirmed = { showLayoutEditDialog = "new layout" to "" },
                neutralButtonText = stringResource(R.string.button_load_custom),
                onNeutral = {
                    showAddLayoutDialog = false
                    layoutPicker.launch(layoutIntent)
                }
            )
        }
    }
}


// from ReorderSwitchPreference
@Composable
private fun PopupOrderDialog(
    onDismissRequest: () -> Unit,
    initialValue: String,
    onConfirmed: (String?) -> Unit,
    title: String,
    showDefault: Boolean
) {
    class KeyAndState(var name: String, var state: Boolean)
    val items = initialValue.split(Separators.ENTRY).map {
        KeyAndState(it.substringBefore(Separators.KV), it.substringAfter(Separators.KV).toBoolean())
    }
    val ctx = LocalContext.current
    ReorderDialog(
        onConfirmed = { reorderedItems ->
            val value = reorderedItems.joinToString(Separators.ENTRY) { it.name + Separators.KV + it.state }
            onConfirmed(value)
        },
        onDismissRequest = onDismissRequest,
        onNeutral = { onDismissRequest(); onConfirmed(null) },
        neutralButtonText = if (showDefault) stringResource(R.string.button_default) else null,
        items = items,
        title = { Text(title) },
        displayItem = { item ->
            var checked by rememberSaveable { mutableStateOf(item.state) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeyboardIconsSet.instance.GetIcon(item.name)
                val text = item.name.lowercase().getStringResourceOrName("popup_keys_", ctx)
                Text(text, Modifier.weight(1f))
                Switch(
                    checked = checked,
                    onCheckedChange = { item.state = it; checked = it }
                )
            }
        },
        getKey = { it.name }
    )
}

private fun getAvailableSecondaryLocales(context: Context, mainLocale: Locale): List<Locale> =
    getDictionaryLocales(context).filter { it != mainLocale && it.script() == mainLocale.script() }

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            SubtypeScreen(SettingsSubtype(Locale.ENGLISH, "")) { }
        }
    }
}
