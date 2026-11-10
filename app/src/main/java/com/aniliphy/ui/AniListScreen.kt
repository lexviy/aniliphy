package com.aniliphy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.SessionState
import kotlinx.coroutines.launch

@Composable
internal fun AniListScreen(appearance: AppearanceState, ui: AniListUi) {
    val state = ui.session.state
    LaunchedEffect(Unit) { ui.session.restore() }
    ProvideCoverStyle(appearance) {
        when (state) {
            SessionState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { Hint("Loading...") }
            SessionState.LoggedOut -> {
                LaunchedEffect(Unit) { ui.navigator.reset() }
                LoginScreen(ui.session)
            }
            SessionState.Guest, is SessionState.LoggedIn -> Sections(ui)
        }
    }
}

@Composable
private fun Sections(ui: AniListUi) {
    val navigator = ui.navigator
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val theme = LocalAppTheme.current
    val top = navigator.stack.lastOrNull()

    BackHandler(enabled = drawer.isOpen || top != null || navigator.section != Section.Home) {
        when {
            drawer.isOpen -> scope.launch { drawer.close() }
            navigator.back() -> Unit
            else -> navigator.select(Section.Home)
        }
    }

    if (top != null) {
        key(top) { PageContent(top) }
        return
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = theme.surface,
                drawerShape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp),
                modifier = Modifier.width(290.dp),
            ) {
                DrawerContent(
                    ui = ui,
                    onSelect = { section ->
                        navigator.select(section)
                        scope.launch { drawer.close() }
                    },
                )
            }
        },
    ) {
        val openMenu = { scope.launch { drawer.open() }; Unit }
        val retained = navigator.retained(navigator.section)
        key(navigator.section) {
            when (navigator.section) {
                Section.Home -> HomeSection(retained, openMenu)
                Section.Anime -> ListSection(MediaKind.Anime, Section.Anime, retained, openMenu)
                Section.Manga -> ListSection(MediaKind.Manga, Section.Manga, retained, openMenu)
                Section.LightNovel -> ListSection(MediaKind.LightNovel, Section.LightNovel, retained, openMenu)
                Section.Characters -> CharactersSection(retained, openMenu)
                Section.Staff -> StaffSection(retained, openMenu)
                Section.Studios -> StudiosSection(retained, openMenu)
                Section.Profile -> ProfileSection(retained, openMenu)
            }
        }
    }
}

@Composable
private fun PageContent(page: Page) {
    when (val route = page.route) {
        is Route.Media -> MediaPage(route.id, page.retained)
        is Route.Character -> CharacterPage(route.id, page.retained)
        is Route.Staff -> StaffPage(route.id, page.retained)
        is Route.Studio -> StudioPage(route.id, page.retained)
    }
}

@Composable
internal fun SectionBar(section: Section, onMenu: () -> Unit, text: String = "", onText: (String) -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
    ) {
        MenuButton(onMenu)
        Spacer(Modifier.width(6.dp))
        val hint = section.searchHint
        if (hint != null) {
            SearchField(text, onText, hint, Modifier.weight(1f))
        } else {
            BasicText(
                section.label,
                style = TextStyle(color = LocalAppTheme.current.foreground, fontSize = 20.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

@Composable
private fun DrawerContent(ui: AniListUi, onSelect: (Section) -> Unit) {
    val theme = LocalAppTheme.current
    val session = ui.session
    val scope = rememberCoroutineScope()
    var confirmLogout by remember { mutableStateOf(false) }
    val viewer = (session.state as? SessionState.LoggedIn)?.viewer

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            if (viewer != null) {
                coil3.compose.AsyncImage(
                    model = viewer.avatar?.best,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(theme.panel),
                )
                Spacer(Modifier.width(12.dp))
            }
            BasicText(
                viewer?.name ?: "Guest",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = theme.foreground, fontSize = 17.sp, fontWeight = FontWeight.Bold),
            )
        }
        Spacer(Modifier.height(6.dp))
        Section.entries.forEach { section ->
            DrawerRow(section, selected = section == ui.navigator.section) { onSelect(section) }
        }
        Spacer(Modifier.height(10.dp))
        if (viewer != null) {
            ActionRow("Log out", onClick = { confirmLogout = true }, color = theme.error)
        } else {
            ActionRow("Log in", onClick = { scope.launch { session.logout() } }, color = theme.primary)
        }
    }

    if (confirmLogout) {
        ConfirmDialog(
            title = "Log out?",
            message = "This removes your AniList token from this phone. Your lists stay on AniList.",
            confirmLabel = "Log out",
            onConfirm = {
                confirmLogout = false
                scope.launch { session.logout() }
            },
            onDismiss = { confirmLogout = false },
        )
    }
}

@Composable
private fun DrawerRow(section: Section, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalAppTheme.current
    val color = if (selected) theme.primary else theme.foreground
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) theme.panel else theme.surface)
            .clickable(role = Role.Tab, onClick = onClick)
            .height(50.dp)
            .padding(horizontal = 12.dp),
    ) {
        Canvas(Modifier.size(24.dp)) {
            section.icon(this, IconPaint(color, cutout = if (selected) theme.panel else theme.surface, filled = selected))
        }
        Spacer(Modifier.width(16.dp))
        BasicText(
            section.label,
            style = TextStyle(color = color, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
        )
    }
}

@Composable
internal fun LoginInvite(message: String) {
    val session = LocalAniList.current.session
    val scope = rememberCoroutineScope()
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().padding(24.dp),
    ) {
        Hint(message)
        PillButton("Log in", onClick = { scope.launch { session.logout() } })
    }
}
