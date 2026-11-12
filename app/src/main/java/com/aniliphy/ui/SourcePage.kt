package com.aniliphy.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.preference.DialogPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.TwoStatePreference
import androidx.preference.bindListener
import androidx.preference.setOnStateChangeListener
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.aniliphy.WidgetOpen
import com.aniliphy.WidgetOpenRequest
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaKind
import com.aniliphy.core.BrowseItem
import com.aniliphy.core.Listing
import com.aniliphy.core.SiteFilter
import com.aniliphy.core.SiteFilters
import com.aniliphy.core.Sources
import com.aniliphy.core.TachiSite
import com.aniliphy.widget.WidgetKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** A Mihon or Aniyomi source's own page: its catalogue to browse, and its settings when it has some. */
@Composable
internal fun SourcePage(site: TachiSite, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var settings by rememberSaveable(site.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BackHeader(title = site.name, onBack = onBack)
        if (site.configurable) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Browse", onClick = { settings = false }, filled = !settings)
                PillButton("Settings", onClick = { settings = true }, filled = settings)
            }
        }
        if (settings) SourceSettings(site, Modifier.weight(1f)) else SourceBrowse(site, Modifier.weight(1f))
    }
}

// ——— Browsing ———

@Composable
private fun SourceBrowse(site: TachiSite, modifier: Modifier) {
    val theme = LocalAppTheme.current
    val scope = rememberCoroutineScope()
    var listing by remember(site) { mutableStateOf(Listing.Popular) }
    var query by remember(site) { mutableStateOf("") }
    val filters by produceState<SiteFilters?>(null, site) { value = withContext(Dispatchers.IO) { runCatching { site.filters() }.getOrNull() } }
    // Bumped to start the list again, as when the listing, the words or the filters change.
    var asked by remember(site) { mutableIntStateOf(0) }
    val items = remember(site) { mutableStateListOf<BrowseItem>() }
    var page by remember(site) { mutableIntStateOf(0) }
    var hasNext by remember(site) { mutableStateOf(true) }
    var loading by remember(site) { mutableStateOf(false) }
    var failed by remember(site) { mutableStateOf<String?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    var tapped by remember { mutableStateOf<BrowseItem?>(null) }
    val linked = rememberLinked(site.isAnime)
    val grid = rememberLazyGridState()

    fun loadNext(round: Int) {
        if (loading || !hasNext) return
        loading = true
        failed = null
        val next = page + 1
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { site.browse(listing, next, query.trim(), filters) } }
            if (round != asked) return@launch
            loading = false
            result.onSuccess { found ->
                val known = items.map { it.url }.toHashSet()
                items.addAll(found.items.filter { known.add(it.url) })
                page = next
                // A page with nothing new would ask for the same again forever.
                hasNext = found.hasNext && found.items.isNotEmpty()
                // The grid holds on to the item it showed, the "loading" line, so a new list would open halfway down.
                if (next == 1) grid.scrollToItem(0)
            }.onFailure { failed = (it as? IOException)?.message ?: "Could not reach ${site.name}" }
        }
    }

    LaunchedEffect(site, asked) {
        items.clear()
        page = 0
        hasNext = true
        loading = false
        failed = null
        grid.scrollToItem(0)
        loadNext(asked)
    }
    // Typing searches once the words settle; clearing them goes back to the popular list.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            if (listing == Listing.Search && filters == null) {
                listing = Listing.Popular
                asked++
            }
            return@LaunchedEffect
        }
        delay(600)
        listing = Listing.Search
        asked++
    }
    val nearEnd by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, loading, hasNext, failed) {
        if (nearEnd && !loading && hasNext && failed == null && page > 0) loadNext(asked)
    }

    Column(modifier) {
        InputBox(
            query,
            { query = it },
            "Search ${site.name}",
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            leading = { SearchLead(query.isNotEmpty()) },
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillButton("Popular", onClick = { query = ""; listing = Listing.Popular; asked++ }, filled = listing == Listing.Popular)
            if (site.supportsLatest) {
                PillButton("Latest", onClick = { query = ""; listing = Listing.Latest; asked++ }, filled = listing == Listing.Latest)
            }
            if (filters?.items?.isNotEmpty() == true) {
                PillButton("Filters", onClick = { showFilters = true }, filled = listing == Listing.Search && query.isBlank())
            }
        }
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Adaptive(108.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(items, key = { _, item -> item.url }) { _, item ->
                BrowseCard(site, item, linked.of(site, item) != null) { tapped = item }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "foot") {
                when {
                    failed != null -> Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Hint(failed.orEmpty(), color = theme.error)
                        PillButton("Try again", onClick = { if (page == 0) asked++ else loadNext(asked) }, filled = false)
                    }
                    loading -> LookLoading("Loading ${site.name}…", Modifier.fillMaxWidth(), theme.foreground.copy(alpha = 0.7f))
                    items.isEmpty() && page > 0 -> NothingFound(Modifier.fillMaxWidth())
                    !hasNext && items.isNotEmpty() -> EndOfList()
                    else -> Spacer(Modifier.height(1.dp))
                }
            }
        }
    }

    if (showFilters) {
        filters?.let { shown ->
            FiltersDialog(
                filters = shown,
                onApply = {
                    showFilters = false
                    listing = Listing.Search
                    asked++
                },
                onReset = {
                    showFilters = false
                    // The source's filter objects keep their state, so a fresh list is the only way back to how they start.
                    scope.launch {
                        val fresh = withContext(Dispatchers.IO) { runCatching { site.filters() }.getOrNull() }
                        if (fresh != null) copyStart(fresh.items, shown.items)
                        listing = if (query.isBlank()) Listing.Popular else Listing.Search
                        asked++
                    }
                },
                onDismiss = { showFilters = false },
            )
        }
    }
    tapped?.let { item -> TitleActions(site, site.isAnime, item, linked, onDismiss = { tapped = null }) }
}

/** Puts each filter in [into] back to the value it has in [start], a fresh copy of the same list. */
private fun copyStart(start: List<SiteFilter>, into: List<SiteFilter>) {
    start.zip(into).forEach { (from, to) ->
        when {
            from is SiteFilter.Text && to is SiteFilter.Text -> to.set(from.get())
            from is SiteFilter.Check && to is SiteFilter.Check -> to.set(from.get())
            from is SiteFilter.Tri && to is SiteFilter.Tri -> to.set(from.get())
            from is SiteFilter.Select && to is SiteFilter.Select -> to.set(from.get())
            from is SiteFilter.Sort && to is SiteFilter.Sort -> to.set(from.get())
            from is SiteFilter.Group && to is SiteFilter.Group -> copyStart(from.items, to.items)
        }
    }
}

@Composable
private fun FiltersDialog(filters: SiteFilters, onApply: () -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    val theme = LocalAppTheme.current
    // The filters are the source's own objects, changed in place; this only redraws them.
    var version by remember { mutableIntStateOf(0) }
    val changed: () -> Unit = { version++ }
    DialogFrame(onDismiss) {
        BasicText("Filters", style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold))
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            key(version) { filters.items.forEach { FilterRow(it, changed) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            PillButton("Reset", onClick = onReset, filled = false)
            PillButton("Apply", onClick = onApply)
        }
    }
}

@Composable
private fun key(version: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(version) { content() }

@Composable
private fun FilterRow(filter: SiteFilter, changed: () -> Unit) {
    val theme = LocalAppTheme.current
    val text = TextStyle(color = theme.foreground, fontSize = 14.sp)
    val row = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 4.dp)
    when (filter) {
        is SiteFilter.Header -> BasicText(
            filter.name,
            style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 12.sp),
            modifier = Modifier.padding(vertical = 6.dp),
        )
        is SiteFilter.Separator -> Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(theme.panel))
        is SiteFilter.Text -> {
            var typed by remember(filter) { mutableStateOf(filter.get()) }
            InputBox(typed, { typed = it; filter.set(it) }, filter.name, Modifier.fillMaxWidth().padding(vertical = 4.dp), background = theme.panel)
        }
        is SiteFilter.Check -> Row(row.clickable { filter.set(!filter.get()); changed() }, verticalAlignment = Alignment.CenterVertically) {
            BasicText(if (filter.get()) "☑" else "☐", style = text.copy(color = theme.primary), modifier = Modifier.width(28.dp))
            BasicText(filter.name, style = text)
        }
        is SiteFilter.Tri -> Row(row.clickable { filter.set((filter.get() + 1) % 3); changed() }, verticalAlignment = Alignment.CenterVertically) {
            val (mark, color) = when (filter.get()) {
                1 -> "✓" to theme.primary
                2 -> "✕" to theme.error
                else -> "○" to theme.foreground.copy(alpha = 0.5f)
            }
            BasicText(mark, style = text.copy(color = color), modifier = Modifier.width(28.dp))
            BasicText(filter.name, style = text)
        }
        is SiteFilter.Select -> {
            var open by remember(filter) { mutableStateOf(false) }
            Row(row.clickable { open = true }, verticalAlignment = Alignment.CenterVertically) {
                BasicText(filter.name, style = text, modifier = Modifier.weight(1f))
                BasicText(filter.values.getOrNull(filter.get()).orEmpty(), style = text.copy(color = theme.primary))
            }
            if (open) {
                ChoiceDialog(filter.name, filter.values, filter.get(), onPick = { filter.set(it); open = false; changed() }, onDismiss = { open = false })
            }
        }
        is SiteFilter.Sort -> {
            BasicText(filter.name, style = text.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 8.dp))
            val chosen = filter.get()
            filter.values.forEachIndexed { index, value ->
                Row(
                    row.clickable {
                        // Tapping the chosen one turns it around, as in Mihon.
                        filter.set(if (chosen?.first == index) index to !chosen.second else index to false)
                        changed()
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val mark = when {
                        chosen?.first != index -> " "
                        chosen.second -> "↑"
                        else -> "↓"
                    }
                    BasicText(mark, style = text.copy(color = theme.primary), modifier = Modifier.width(28.dp))
                    BasicText(value, style = text)
                }
            }
        }
        is SiteFilter.Group -> {
            var open by remember(filter) { mutableStateOf(false) }
            Row(row.clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                BasicText(filter.name, style = text.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                BasicText(if (open) "▴" else "▾", style = text)
            }
            if (open) Column(Modifier.padding(start = 12.dp)) { filter.items.forEach { FilterRow(it, changed) } }
        }
    }
}

/** One choice among [values], marked at [selected]. */
@Composable
private fun ChoiceDialog(title: String, values: List<String>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val theme = LocalAppTheme.current
    DialogFrame(onDismiss) {
        BasicText(title, style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold))
        LazyColumn(Modifier.heightIn(max = 420.dp).fillMaxWidth()) {
            items(values.indices.toList()) { index ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(index) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        if (index == selected) "●" else "○",
                        style = TextStyle(color = theme.primary, fontSize = 14.sp),
                        modifier = Modifier.width(28.dp),
                    )
                    BasicText(values[index], style = TextStyle(color = theme.foreground, fontSize = 14.sp))
                }
            }
        }
    }
}

/**
 * Ties a browsed title to its AniList entry: the title then opens from AniList with this source, like one found
 * from its page. Nothing is played or read here, and nothing changes on AniList.
 */
// ——— Settings ———

@Composable
private fun SourceSettings(site: TachiSite, modifier: Modifier) {
    val context = LocalContext.current
    val model by produceState<SourcePreferences?>(null, site) {
        // Reading a value loads the file, which shouldn't happen on the main thread.
        val preferences = withContext(Dispatchers.IO) { site.preferences()?.apply { all } }
        value = runCatching { SourcePreferences(context, site, preferences) }.getOrNull()
    }
    val shown = model
    if (shown == null) {
        LookLoading("Loading its settings…", modifier.padding(20.dp), LocalAppTheme.current.foreground.copy(alpha = 0.7f))
        return
    }
    DisposableEffect(shown) {
        onDispose {
            shown.dispose()
            // Sources read some settings once, when they are made, so they are made again after a change.
            if (shown.changed) Sources.reload()
        }
    }
    val rows = remember(shown.version) { shown.rows() }
    LazyColumn(modifier.fillMaxWidth()) {
        if (rows.isEmpty()) item { Empty("${site.name} has no settings to show.") }
        items(rows) { PreferenceRow(it, shown) }
        item { Spacer(Modifier.height(24.dp)) }
    }
    shown.dialog?.let { PreferenceDialog(it, shown) { shown.dialog = null } }
}

/**
 * The screen a source fills in its `setupPreferenceScreen`. As in Mihon, the androidx.preference objects stay the model,
 * so extensions keep the library's exact behaviour, and are only drawn here.
 */
@SuppressLint("RestrictedApi")
private class SourcePreferences(context: Context, site: TachiSite, preferences: SharedPreferences?) {
    // Bumped whenever the library would redraw a preference.
    var version by mutableIntStateOf(0)
        private set

    var dialog by mutableStateOf<DialogPreference?>(null)

    /** Whether anything was changed, so the source is made again with it. */
    var changed = false

    private val screen: PreferenceScreen

    init {
        val manager = PreferenceManager(context)
        if (preferences != null) manager.preferenceDataStore = PreferencesStore(preferences)
        manager.setOnDisplayPreferenceDialogListener { dialog = it as? DialogPreference }
        screen = manager.createPreferenceScreen(context)
        site.setupPreferences(screen)
        manager.setPreferences(screen)
        screen.onAttached()
        observe(screen)
    }

    private fun observe(preference: Preference) {
        preference.setOnStateChangeListener {
            // A group changing may mean preferences were added to it.
            if (it is PreferenceGroup) observe(it)
            version++
        }
        if (preference is PreferenceGroup) repeat(preference.preferenceCount) { observe(preference.getPreference(it)) }
    }

    fun rows(): List<PreferenceShown> = buildList { addVisible(screen) }

    private fun MutableList<PreferenceShown>.addVisible(group: PreferenceGroup) {
        repeat(group.preferenceCount) {
            val preference = group.getPreference(it)
            if (!preference.isVisible) return@repeat
            add(PreferenceShown.of(preference))
            if (preference is PreferenceGroup) addVisible(preference)
        }
    }

    fun click(preference: Preference) {
        preference.performClick()
        if (preference is TwoStatePreference) changed = true
        version++
    }

    fun dispose() {
        screen.onDetached()
    }
}

/** What a preference shows, read once per change. */
private data class PreferenceShown(
    val preference: Preference,
    val title: String?,
    val summary: String?,
    val enabled: Boolean,
    val checked: Boolean?,
) {
    companion object {
        fun of(preference: Preference): PreferenceShown {
            val summary = when {
                preference !is TwoStatePreference -> runCatching { preference.summary }.getOrNull()
                preference.isChecked && !preference.summaryOn.isNullOrEmpty() -> preference.summaryOn
                !preference.isChecked && !preference.summaryOff.isNullOrEmpty() -> preference.summaryOff
                else -> preference.summary
            }
            return PreferenceShown(
                preference = preference,
                title = preference.title?.toString(),
                summary = summary?.toString(),
                enabled = preference.isEnabled,
                checked = (preference as? TwoStatePreference)?.isChecked,
            )
        }
    }
}

@Composable
private fun PreferenceRow(shown: PreferenceShown, model: SourcePreferences) {
    val preference = shown.preference
    when {
        preference is PreferenceGroup -> SectionTitle(shown.title.orEmpty())
        shown.checked != null -> SwitchRow(
            label = shown.title.orEmpty(),
            checked = shown.checked,
            onChange = { model.click(preference) },
            description = shown.summary,
            enabled = shown.enabled,
        )
        else -> ActionRow(
            label = shown.title.orEmpty(),
            description = shown.summary,
            enabled = shown.enabled && preference.isSelectable,
            onClick = { model.click(preference) },
        )
    }
}

@Composable
private fun PreferenceDialog(preference: DialogPreference, model: SourcePreferences, onDismiss: () -> Unit) {
    val title = (preference.dialogTitle?.takeIf { it.isNotEmpty() } ?: preference.title)?.toString().orEmpty()
    fun saved() {
        model.changed = true
        onDismiss()
    }
    when (preference) {
        is ListPreference -> {
            val values = preference.entryValues.orEmpty().map { it.toString() }
            val names = preference.entries.orEmpty().map { it.toString() }
            ChoiceDialog(
                title = title,
                values = names,
                selected = values.indexOf(preference.value),
                onPick = { index ->
                    val value = values.getOrNull(index)
                    if (value != null && preference.callChangeListener(value)) preference.value = value
                    saved()
                },
                onDismiss = onDismiss,
            )
        }
        is MultiSelectListPreference -> MultiChoiceDialog(preference, title, onSave = { chosen ->
            if (chosen != preference.values && preference.callChangeListener(chosen)) preference.values = chosen
            saved()
        }, onDismiss = onDismiss)
        is EditTextPreference -> EditTextDialog(preference, title, onSave = { text ->
            if (preference.callChangeListener(text)) preference.text = text
            saved()
        }, onDismiss = onDismiss)
        else -> LaunchedEffect(preference) { onDismiss() }
    }
}

@Composable
private fun MultiChoiceDialog(preference: MultiSelectListPreference, title: String, onSave: (HashSet<String>) -> Unit, onDismiss: () -> Unit) {
    val theme = LocalAppTheme.current
    val values = preference.entryValues.orEmpty().map { it.toString() }
    val names = preference.entries.orEmpty().map { it.toString() }
    var chosen by remember { mutableStateOf(preference.values.toSet()) }
    DialogFrame(onDismiss) {
        BasicText(title, style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold))
        LazyColumn(Modifier.heightIn(max = 400.dp).fillMaxWidth()) {
            items(values.indices.toList()) { index ->
                val value = values[index]
                val on = value in chosen
                Row(
                    Modifier.fillMaxWidth().clickable { chosen = if (on) chosen - value else chosen + value }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(if (on) "☑" else "☐", style = TextStyle(color = theme.primary, fontSize = 15.sp), modifier = Modifier.width(28.dp))
                    BasicText(names.getOrElse(index) { value }, style = TextStyle(color = theme.foreground, fontSize = 14.sp))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            PillButton("Cancel", onClick = onDismiss, filled = false)
            PillButton("Save", onClick = { onSave(HashSet(chosen)) })
        }
    }
}

/**
 * Extensions get a real [EditText] through [EditTextPreference.OnBindEditTextListener], and some check what is typed by
 * turning off the dialog's `android.R.id.button1`, found from the field's root view. One stands in for it, unseen.
 */
@SuppressLint("RestrictedApi")
@Composable
private fun EditTextDialog(preference: EditTextPreference, title: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val theme = LocalAppTheme.current
    var field by remember { mutableStateOf<EditText?>(null) }
    var canSave by remember { mutableStateOf(true) }
    DialogFrame(onDismiss) {
        BasicText(title, style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold))
        preference.dialogMessage?.toString()?.takeIf { it.isNotBlank() }?.let {
            BasicText(it, style = TextStyle(color = theme.foreground.copy(alpha = 0.75f), fontSize = 13.sp))
        }
        AndroidView(
            factory = { context ->
                LinearLayout(context).apply {
                    addView(object : Button(context) {
                        init {
                            id = android.R.id.button1
                            visibility = GONE
                        }

                        override fun setEnabled(enabled: Boolean) {
                            super.setEnabled(enabled)
                            canSave = enabled
                        }
                    })
                    val edit = EditText(context).apply {
                        setText(preference.text)
                        setSelection(text.length)
                        setTextColor(android.graphics.Color.argb(255, (theme.foreground.red * 255).toInt(), (theme.foreground.green * 255).toInt(), (theme.foreground.blue * 255).toInt()))
                        preference.bindListener()?.onBindEditText(this)
                        post { requestFocus() }
                    }
                    addView(edit, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    field = edit
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            PillButton("Cancel", onClick = onDismiss, filled = false)
            PillButton("Save", enabled = canSave, onClick = { field?.text?.toString()?.let(onSave) ?: onDismiss() })
        }
    }
}

/** Keeps a source's settings where Mihon and Aniyomi keep them, so they read the same file. */
private class PreferencesStore(private val prefs: SharedPreferences) : PreferenceDataStore() {
    override fun getBoolean(key: String?, defValue: Boolean) = prefs.getBoolean(key, defValue)
    override fun putBoolean(key: String?, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    override fun getInt(key: String?, defValue: Int) = prefs.getInt(key, defValue)
    override fun putInt(key: String?, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun getLong(key: String?, defValue: Long) = prefs.getLong(key, defValue)
    override fun putLong(key: String?, value: Long) = prefs.edit().putLong(key, value).apply()
    override fun getFloat(key: String?, defValue: Float) = prefs.getFloat(key, defValue)
    override fun putFloat(key: String?, value: Float) = prefs.edit().putFloat(key, value).apply()
    override fun getString(key: String?, defValue: String?) = prefs.getString(key, defValue)
    override fun putString(key: String?, value: String?) = prefs.edit().putString(key, value).apply()
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = prefs.getStringSet(key, defValues)
    override fun putStringSet(key: String?, values: MutableSet<String>?) = prefs.edit().putStringSet(key, values).apply()
}
