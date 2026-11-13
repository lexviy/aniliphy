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
    Calendar("Calendar", null, CalendarIcon),
    Seasons("Seasons", null, SeasonIcon),
    Characters("Characters", "Search characters", CharacterIcon),
    Staff("Staff", "Search staff", StaffIcon),
    Studios("Studios", "Search studios", StudioIcon),
    Profile("Profile", null, ProfileIcon),
    Social("Social", null, PeopleIcon),
    Forum("Forum", null, ForumIcon),
    Inbox("Inbox", null, BellIcon),
}

/** Sections that are tabs of their own, never in the AniList tab's menu. */
internal val TAB_SECTIONS = setOf(Section.Profile, Section.Social, Section.Forum, Section.Inbox)

internal sealed interface Route {
    data class Media(val id: Int) : Route
    data class Character(val id: Int) : Route
    data class Staff(val id: Int) : Route
    data class Studio(val id: Int) : Route
    /** A franchise in watch order, found from one of its titles. */
    data class Franchise(val id: Int) : Route
    /** Someone's profile, by id, or by name when only that is known (an anilist.co/user/ link). */
    data class User(val id: Int? = null, val name: String? = null) : Route
    /** A forum thread; [focusComment] scrolls to one comment (from an anilist.co comment link). */
    data class Thread(val id: Int, val focusComment: Int? = null) : Route
    /** Starting a thread, about [mediaId] when it's opened from a title. */
    data class NewThread(val mediaId: Int? = null) : Route
    /** A title's forum threads. */
    data class MediaThreads(val mediaId: Int) : Route
    /** One activity and its replies (from a notification). */
    data class Activity(val id: Int) : Route
    /** The Planning roulette, starting on [kind] (a MediaKind ordinal). */
    data class Roulette(val kind: Int = 0) : Route
}

internal class Retained {
    private val values = HashMap<String, Any?>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String, create: () -> T): T = values.getOrPut(key) { create() } as T
}

internal class Page(val route: Route) {
    val retained = Retained()
}

/** Counts changes to your lists, shared by the AniList and Profile tabs so a change in one refreshes the other. */
internal class ListsClock {
    var version by mutableStateOf(0)
}

internal class AniListNavigator(
    section: Section = Section.Home,
    routes: List<Route> = emptyList(),
    val clock: ListsClock = ListsClock(),
) {
    var section by mutableStateOf(section)
    val stack = mutableStateListOf<Page>().apply { addAll(routes.map(::Page)) }
    private var sections = HashMap<Section, Retained>()
    private var seenVersion = clock.version

    fun retained(section: Section): Retained {
        // Lists changed (maybe from the other tab): the sections showing them start over.
        if (seenVersion != clock.version) {
            seenVersion = clock.version
            listOf(Section.Home, Section.Anime, Section.Manga, Section.LightNovel).forEach { sections.remove(it) }
        }
        return sections.getOrPut(section) { Retained() }
    }

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

    val listsVersion: Int get() = clock.version

    fun listsChanged() {
        clock.version++
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
                // Profile has its own tab now; an AniList tab left on it opens on Home.
                val section = Section.entries.firstOrNull { it.name == saved.first() }?.takeIf { it !in TAB_SECTIONS } ?: Section.Home
                AniListNavigator(section, saved.drop(1).mapNotNull(::decode))
            },
        )

        private fun encode(route: Route) = when (route) {
            is Route.Media -> "media:${route.id}"
            is Route.Character -> "character:${route.id}"
            is Route.Staff -> "staff:${route.id}"
            is Route.Studio -> "studio:${route.id}"
            is Route.Franchise -> "franchise:${route.id}"
            is Route.User -> if (route.id != null) "user:${route.id}" else "username:${route.name}"
            is Route.Thread -> "thread:${route.id}"
            is Route.NewThread -> "newthread:${route.mediaId ?: 0}"
            is Route.MediaThreads -> "mediathreads:${route.mediaId}"
            is Route.Activity -> "activity:${route.id}"
            is Route.Roulette -> "roulette:${route.kind}"
        }

        private fun decode(text: String): Route? {
            if (text.startsWith("username:")) return Route.User(name = text.substringAfter(':'))
            val id = text.substringAfter(':').toIntOrNull() ?: return null
            return when (text.substringBefore(':')) {
                "media" -> Route.Media(id)
                "character" -> Route.Character(id)
                "staff" -> Route.Staff(id)
                "studio" -> Route.Studio(id)
                "franchise" -> Route.Franchise(id)
                "user" -> Route.User(id)
                "thread" -> Route.Thread(id)
                "newthread" -> Route.NewThread(id.takeIf { it > 0 })
                "mediathreads" -> Route.MediaThreads(id)
                "activity" -> Route.Activity(id)
                "roulette" -> Route.Roulette(id)
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
    /** Another tab's AniList state to share your list and removals with (the Profile tab shares the AniList tab's). */
    shared: AniListUi? = null,
) {
    /** The same AniList, with its own pages on [navigator]: for a tab of its own, like Profile. */
    fun forTab(navigator: AniListNavigator) = AniListUi(api, session, scope, navigator, this)

    fun <T> load(autoStart: Boolean = true, block: suspend (refresh: Boolean) -> Fetched<T>) =
        LoadState(this, block, autoStart)

    fun <T> paged(key: (T) -> Any, block: suspend (page: Int) -> Fetched<Paged<T>>) = PagedState(this, key, block)

    fun describe(e: AniListException): String {
        if (e is InvalidTokenException) scope.launch { session.tokenRejected() }
        return e.message ?: "AniList error"
    }

    val onList: ListIndex = shared?.onList ?: ListIndex({ api.animeList(it) }, { api.mangaList(it) })

    /** The title's entry on your list, unless it isn't there (or is being taken off). */
    fun entryFor(mediaId: Int): com.aniliphy.anilist.ListEntry? =
        onList.entries[mediaId]?.takeIf { mediaId !in removals.hidden }

    val removals: ListRemovals = shared?.removals ?: ListRemovals(api::deleteEntry, scope, ::describe) { navigator.listsChanged() }
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
