package com.aniliphy.widget

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Opened by tapping a column's name on the widget: that column's covers on a turning wheel over the home screen.
 * Swipe to turn it, tap the cover in the middle to open it like the widget does, tap anywhere else to close.
 */
class WidgetWheelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val column = intent.getStringExtra(WidgetIntents.EXTRA_COLUMN)
            ?.let { name -> WidgetColumn.entries.firstOrNull { it.name == name } } ?: WidgetColumn.Anime
        val items = column.items(WidgetData.read(this))
        if (items.isEmpty()) {
            finish()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = BLUR }
        }
        val colors = WidgetColors.of(this)
        val dir = WidgetData.images(this)
        setContent {
            var images by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
            LaunchedEffect(Unit) {
                images = withContext(Dispatchers.IO) {
                    items.mapNotNull { it.image }.distinct().mapNotNull { name ->
                        BitmapFactory.decodeFile(File(dir, name).path)?.let { name to it.asImageBitmap() }
                    }.toMap()
                }
            }
            Wheel(items, images, colors, onOpen = ::open, onClose = ::finish)
        }
    }

    private fun open(item: WidgetItem) {
        startActivity(WidgetIntents.open(this, item, "wheel").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private companion object {
        const val BLUR = 60
    }
}

/** At least this many places on the wheel, so a few covers don't spread all the way round. */
private const val MIN_SLOTS = 6

@Composable
private fun Wheel(
    items: List<WidgetItem>,
    images: Map<String, ImageBitmap>,
    colors: WidgetColors,
    onOpen: (WidgetItem) -> Unit,
    onClose: () -> Unit,
) {
    val count = items.size
    val slots = maxOf(count, MIN_SLOTS)
    val step = 360f / slots
    val loops = count >= MIN_SLOTS
    val rotation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    /** Turning by one cover takes this much swiping. */
    val degreesPerPx = step / with(density) { 110.dp.toPx() }

    fun indexAt(value: Float): Int {
        val index = (-value / step).roundToInt()
        return if (loops) Math.floorMod(index, count) else index.coerceIn(0, count - 1)
    }
    fun bounded(value: Float) = if (loops) value else value.coerceIn(-(count - 1) * step, 0f)
    fun turnTo(target: Float) = scope.launch {
        rotation.animateTo(bounded(target), spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow))
    }
    fun turnToIndex(index: Int) {
        var target = -index * step
        if (loops) while (abs(target - rotation.value) > 180f) target += if (target < rotation.value) 360f else -360f
        turnTo(target)
    }

    val front = indexAt(rotation.value)
    var lastFront by remember { mutableIntStateOf(front) }
    LaunchedEffect(front) {
        if (front != lastFront) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        lastFront = front
    }

    val quiet = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(count) {
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragEnd = {
                        val fling = tracker.calculateVelocity().x * degreesPerPx * 0.18f
                        val target = ((rotation.value + fling) / step).roundToInt() * step
                        turnTo(target)
                    },
                ) { change, dx ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    change.consume()
                    scope.launch { rotation.snapTo(bounded(rotation.value + dx * degreesPerPx)) }
                }
            }
            .clickable(interactionSource = quiet, indication = null, onClick = onClose),
    ) {
        Column(Modifier.align(Alignment.Center).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.fillMaxWidth().height(380.dp)) {
                val radius = maxWidth * 0.36f
                items.indices
                    .map { index -> index to (index * step + rotation.value) * (PI / 180).toFloat() }
                    .sortedBy { (_, angle) -> cos(angle) }
                    .forEach { (index, angle) ->
                        val depth = (cos(angle) + 1) / 2
                        val item = items[index]
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .offset(x = radius * sin(angle), y = -(120.dp * (1 - depth)) + 50.dp)
                                .zIndex(depth)
                                .graphicsLayer {
                                    val scale = 0.5f + 0.5f * depth
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = 0.3f + 0.7f * depth
                                }
                                .size(150.dp, 213.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(colors.panel))
                                .clickable { if (index == front) onOpen(item) else turnToIndex(index) },
                        ) {
                            // The full cover, like in the app; the widget's small copy shows until it has loaded.
                            val small = item.image?.let(images::get)?.let(::BitmapPainter)
                            val model = item.cover?.let { cover -> cover.removePrefix("file://").takeIf { it.startsWith("/") }?.let(::File) ?: cover }
                            if (model != null || small != null) {
                                AsyncImage(
                                    model = model,
                                    contentDescription = item.title,
                                    placeholder = small,
                                    error = small,
                                    fallback = small,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                val marks = remember(item, colors) { WidgetData.Marks.of(item, colors) }
                                Canvas(Modifier.fillMaxSize()) {
                                    drawIntoCanvas { WidgetData.drawMarks(it.nativeCanvas, size.width, size.height, marks) }
                                }
                            } else {
                                Text(
                                    item.title,
                                    Modifier.align(Alignment.Center).padding(12.dp),
                                    color = Color(colors.foreground),
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
            }
            Spacer(Modifier.height(20.dp))
            val item = items[front]
            Text(
                item.title,
                Modifier.padding(horizontal = 32.dp),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.detail,
                Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Swipe to turn · tap the middle cover to open",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
            )
        }
    }
}
