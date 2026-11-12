package com.aniliphy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One entry of the Settings list. */
internal class MenuItem(val label: String, val icon: TabIcon, val onClick: () -> Unit)

/**
 * The Settings list in the looks that have their own: Genga's numbered cut sheet, Bunko's table of contents, a
 * channel list, a JRPG command window, a line of stations, a receipt, a stamp card and a glass panel. The numbers are
 * only the order of the list.
 */
@Composable
internal fun StyledMenu(items: List<MenuItem>) {
    val theme = LocalAppTheme.current
    when (Look.style) {
        UiStyle.Genga -> MenuFrame(Modifier.border(1.dp, theme.hairline).background(theme.background)) {
            items.forEachIndexed { index, item -> GengaRow(index, item, last = index == items.lastIndex) }
        }
        UiStyle.Bunko -> MenuFrame {
            BasicText("目次", style = TitleStyle(color = theme.primary, fontSize = 15.sp), modifier = Modifier.padding(bottom = 6.dp))
            DoubleRule(theme.hairline, Modifier.fillMaxWidth())
            items.forEachIndexed { index, item -> BunkoRow(index, item) }
        }
        UiStyle.Shinya -> MenuFrame {
            items.forEachIndexed { index, item -> ShinyaRow(item, last = index == items.lastIndex) }
        }
        UiStyle.Jrpg -> MenuFrame(Modifier.rpgWindow(theme).padding(vertical = 6.dp)) {
            items.forEach { JrpgRow(it) }
        }
        UiStyle.Eki -> MenuFrame {
            items.forEachIndexed { index, item -> EkiRow(index, item, first = index == 0, last = index == items.lastIndex) }
        }
        UiStyle.Konbini -> MenuFrame(Modifier.printOnce("receipt-settings").clip(ReceiptShape).background(theme.paper).padding(vertical = 14.dp)) {
            items.forEach { KonbiniRow(it) }
        }
        UiStyle.Stamp -> MenuFrame {
            items.forEachIndexed { index, item -> StampRow(index, item, last = index == items.lastIndex) }
        }
        UiStyle.Glass -> MenuFrame(Modifier.glass(theme, RoundedCornerShape(26.dp)).padding(vertical = 6.dp)) {
            items.forEachIndexed { index, item -> GlassRow(item, last = index == items.lastIndex) }
        }
        UiStyle.Tankobon, UiStyle.Modern -> Unit
    }
}

@Composable
private fun MenuFrame(frame: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().then(frame), content = content)
}

@Composable
private fun MenuIcon(icon: TabIcon, color: Color, cutout: Color, size: Int = 22) {
    Canvas(Modifier.size(size.dp)) { icon(this, IconPaint(color, cutout = cutout, filled = false)) }
}

private fun Modifier.menuTap(onClick: () -> Unit): Modifier = clickable(role = Role.Button, onClick = onClick)

@Composable
private fun GengaRow(index: Int, item: MenuItem, last: Boolean) {
    val theme = LocalAppTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .drawBehind {
                if (!last) drawLine(theme.hairline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                drawLine(theme.hairline, Offset(48.dp.toPx(), 0f), Offset(48.dp.toPx(), size.height), 1.dp.toPx())
            }
            .menuTap(item.onClick),
    ) {
        BasicText(
            "%02d".format(index + 1),
            style = NumberStyle(color = theme.primary, fontSize = 14.sp, bold = true),
            modifier = Modifier.width(48.dp).padding(start = 12.dp),
        )
        Spacer(Modifier.width(12.dp))
        MenuIcon(item.icon, theme.foreground.copy(alpha = 0.8f), theme.background, 20)
        Spacer(Modifier.width(12.dp))
        BasicText(item.label, style = TextStyle(color = theme.ink, fontSize = 16.sp), modifier = Modifier.padding(vertical = 10.dp))
    }
}

private val KANJI_MENU = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")

@Composable
private fun BunkoRow(index: Int, item: MenuItem) {
    val theme = LocalAppTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).menuTap(item.onClick),
    ) {
        BasicText(
            KANJI_MENU.getOrNull(index) ?: (index + 1).toString(),
            style = TitleStyle(color = theme.primary, fontSize = 17.sp),
            modifier = Modifier.widthIn(min = 34.dp),
        )
        BasicText(item.label, style = TextStyle(color = theme.ink, fontSize = 16.sp), modifier = Modifier.padding(vertical = 10.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(20.dp).leaders(theme.hairline))
        Spacer(Modifier.width(10.dp))
        MenuIcon(item.icon, theme.primary, theme.background, 20)
    }
}

@Composable
private fun ShinyaRow(item: MenuItem, last: Boolean) {
    val theme = LocalAppTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .drawBehind { if (!last) drawLine(theme.rule, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .menuTap(item.onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(theme.deepPrimary).scanlines(),
        ) {
            MenuIcon(item.icon, Color.White, theme.deepPrimary, 22)
        }
        Spacer(Modifier.width(14.dp))
        BasicText(item.label, style = TextStyle(color = theme.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(vertical = 10.dp))
    }
}

@Composable
private fun JrpgRow(item: MenuItem) {
    val theme = LocalAppTheme.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button, onClick = item.onClick)
            .padding(horizontal = 12.dp),
    ) {
        BasicText(
            "▶",
            style = NumberStyle(color = if (pressed) theme.accent else Color.Transparent, fontSize = 14.sp),
            modifier = Modifier.width(22.dp),
        )
        BasicText(item.label, style = TextStyle(color = theme.ink, fontSize = 16.sp), modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        MenuIcon(item.icon, theme.foreground.copy(alpha = 0.8f), theme.surface, 20)
    }
}

@Composable
private fun EkiRow(index: Int, item: MenuItem, first: Boolean, last: Boolean) {
    val theme = LocalAppTheme.current
    val line = theme.ekiLine(1)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 56.dp).menuTap(item.onClick),
    ) {
        Box(
            Modifier.width(36.dp).fillMaxHeight().drawBehind {
                val x = size.width / 2
                val mid = size.height / 2
                val thick = 6.dp.toPx()
                drawLine(line, Offset(x, if (first) mid else 0f), Offset(x, if (last) mid else size.height), thick)
                drawCircle(EkiPlate, 8.dp.toPx(), Offset(x, mid))
                drawCircle(line, 8.dp.toPx(), Offset(x, mid), style = Stroke(3.dp.toPx()))
            },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            BasicText("%02d".format(index + 1), style = NumberStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 11.sp))
            BasicText(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        }
        MenuIcon(item.icon, theme.foreground.copy(alpha = 0.8f), theme.background, 20)
    }
}

@Composable
private fun KonbiniRow(item: MenuItem) {
    val theme = LocalAppTheme.current
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).menuTap(item.onClick).padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        BasicText(item.label.uppercase(), style = TextStyle(color = theme.paperInk, fontSize = 14.sp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f).height(16.dp).leaders(theme.paperInk.copy(alpha = 0.45f)))
        Spacer(Modifier.width(6.dp))
        BasicText(">", style = NumberStyle(color = theme.paperInk, fontSize = 14.sp))
    }
}

@Composable
private fun StampRow(index: Int, item: MenuItem, last: Boolean) {
    val theme = LocalAppTheme.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).menuTap(item.onClick),
        ) {
            StampMark((index + 1).toString(), theme.primary, 40.dp, tilt = STAMP_TILT[index % STAMP_TILT.size])
            Spacer(Modifier.width(14.dp))
            BasicText(item.label, style = TextStyle(color = theme.ink, fontSize = 16.sp), modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            MenuIcon(item.icon, theme.foreground.copy(alpha = 0.7f), theme.background, 20)
        }
        if (!last) DashedRule(theme.hairline, Modifier.fillMaxWidth())
    }
}

@Composable
private fun GlassRow(item: MenuItem, last: Boolean) {
    val theme = LocalAppTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .drawBehind {
                if (!last) drawLine(theme.glassEdge, Offset(56.dp.toPx(), size.height), Offset(size.width, size.height), 1.dp.toPx())
            }
            .menuTap(item.onClick)
            .padding(horizontal = 16.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(theme.primary.copy(alpha = 0.22f)),
        ) {
            MenuIcon(item.icon, theme.ink, theme.surface, 18)
        }
        Spacer(Modifier.width(12.dp))
        BasicText(item.label, style = TextStyle(color = theme.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(vertical = 10.dp))
    }
}
