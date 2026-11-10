package com.aniliphy.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.aniliphy.anilist.AniListApi
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.AniListServices
import com.aniliphy.anilist.AniListSession
import com.aniliphy.anilist.Fetched
import com.aniliphy.anilist.InvalidTokenException
import com.aniliphy.anilist.Paged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class Section(val label: String, val searchHint: String?, val icon: TabIcon) {
    Home("Home", "Search all of AniList", HouseIcon),
    Anime("Anime", "Search your anime", PlayIcon),
    Manga("Manga", "Search your manga", MangaPageIcon),
    LightNovel("Light Novel", "Search your light novels", BookIcon),
    Characters("Characters", "Search characters", CharacterIcon),
    Staff("Staff", "Search staff", StaffIcon),
    Studios("Studios", "Search studios", StudioIcon),
    Profile("Profile", null, ProfileIcon),
}

internal sealed interface Route {
    data class Media(val id: Int) : Route
    data class Character(val id: Int) : Route
    data class Staff(val id: Int) : Route
    data class Studio(val id: Int) : Route
}

internal class Retained {
    private val values = HashMap<String, Any?>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String, create: () -> T): T = values.getOrPut(key) { create() } as T
}

internal class Page(val route: Route) {
    val retained = Retained()
}

internal class AniListNavigator(section: Section = Section.Home, routes: List<Route> = emptyList()) {
    var section by mutableStateOf(section)
    val stack = mutableStateListOf<Page>().apply { addAll(routes.map(::Page)) }
    private var sections = HashMap<Section, Retained>()

    fun retained(section: Section): Retained = sections.getOrPut(section) { Retained() }

    fun open(route: Route) {
        stack += Page(route)
    }

    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun select(section: Section) {
        stack.clear()
        this.section = section
    }

    var listsVersion by mutableStateOf(0)
        private set

    fun listsChanged() {
        listOf(Section.Home, Section.Anime, Section.Manga, Section.LightNovel).forEach { sections.remove(it) }
        listsVersion++
    }

    fun reset() {
        stack.clear()
        section = Section.Home
        sections = HashMap()
    }

    companion object {
        val Saver: Saver<AniListNavigator, Any> = listSaver(
            save = { navigator -> listOf(navigator.section.name) + navigator.stack.map { encode(it.route) } },
            restore = { saved ->
                val section = Section.entries.firstOrNull { it.name == saved.first() } ?: Section.Home
                AniListNavigator(section, saved.drop(1).mapNotNull(::decode))
            },
        )

        private fun encode(route: Route) = when (route) {
            is Route.Media -> "media:${route.id}"
            is Route.Character -> "character:${route.id}"
            is Route.Staff -> "staff:${route.id}"
            is Route.Studio -> "studio:${route.id}"
        }

        private fun decode(text: String): Route? {
            val id = text.substringAfter(':').toIntOrNull() ?: return null
            return when (text.substringBefore(':')) {
                "media" -> Route.Media(id)
                "character" -> Route.Character(id)
                "staff" -> Route.Staff(id)
                "studio" -> Route.Studio(id)
                else -> null
            }
        }
    }
}

internal class LoadState<T>(
    private val ui: AniListUi,
    private var block: suspend (refresh: Boolean) -> Fetched<T>,
    autoStart: Boolean,
) {
    var value by mutableStateOf<T?>(null)
        private set
    var stale by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    private var job: Job? = null
    private var run = 0

    init {
        if (autoStart) load()
    }

    fun load(refresh: Boolean = false) {
        job?.cancel()
        val current = ++run
        loading = true
        error = null
        job = ui.scope.launch {
            try {
                val fetched = withContext(Dispatchers.Default) { block(refresh) }
                if (current == run) {
                    value = fetched.value
                    stale = fetched.stale
                }
            } catch (e: AniListException) {
                if (current == run) error = ui.describe(e)
            } finally {
                if (current == run) loading = false
            }
        }
    }

    fun restart(newBlock: suspend (refresh: Boolean) -> Fetched<T>) {
        block = newBlock
        value = null
        stale = false
        load()
    }
}

internal class PagedState<T>(
    private val ui: AniListUi,
    private val key: (T) -> Any,
    private var block: suspend (page: Int) -> Fetched<Paged<T>>,
) {
    var items by mutableStateOf<List<T>>(emptyList())
        private set
    var hasNext by mutableStateOf(true)
        private set
    var stale by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    private var nextPage = 1
    private var job: Job? = null
    private var run = 0

    init {
        loadMore()
    }

    fun loadMore() {
        if (loading || !hasNext) return
        val current = ++run
        val page = nextPage
        loading = true
        error = null
        job = ui.scope.launch {
            try {
                val fetched = withContext(Dispatchers.Default) { block(page) }
                if (current == run) {
                    items = (items + fetched.value.items).distinctBy(key)
                    hasNext = fetched.value.hasNext
                    stale = stale || fetched.stale
                    nextPage = page + 1
                }
            } catch (e: AniListException) {
                if (current == run) error = ui.describe(e)
            } finally {
                if (current == run) loading = false
            }
        }
    }

    fun restart(newBlock: suspend (page: Int) -> Fetched<Paged<T>>) {
        job?.cancel()
        block = newBlock
        items = emptyList()
        hasNext = true
        stale = false
        nextPage = 1
        loading = false
        loadMore()
    }

    fun retry() {
        error = null
        loadMore()
    }
}

internal class AniListUi(
    val api: AniListApi,
    val session: AniListSession,
    val scope: CoroutineScope,
    val navigator: AniListNavigator,
) {
    fun <T> load(autoStart: Boolean = true, block: suspend (refresh: Boolean) -> Fetched<T>) =
        LoadState(this, block, autoStart)

    fun <T> paged(key: (T) -> Any, block: suspend (page: Int) -> Fetched<Paged<T>>) = PagedState(this, key, block)

    fun describe(e: AniListException): String {
        if (e is InvalidTokenException) scope.launch { session.tokenRejected() }
        return e.message ?: "AniList error"
    }
}

internal val LocalAniList = staticCompositionLocalOf<AniListUi> { error("No AniList state") }

@Composable
internal fun rememberAniListUi(): AniListUi {
    val context = LocalContext.current
    val services = remember { AniListServices.get(context) }
    val scope = rememberCoroutineScope()
    val navigator = rememberSaveable(saver = AniListNavigator.Saver) { AniListNavigator() }
    return remember { AniListUi(services.api, services.session, scope, navigator) }
}
