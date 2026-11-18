package com.aniliphy.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A look's own colours, as "#rrggbb". */
@Serializable
data class LookColors(
    val background: String,
    val surface: String,
    val foreground: String,
    val primary: String,
    val secondary: String,
    val accent: String,
) {
    fun all(): List<Pair<String, String>> = listOf(
        "background" to background, "surface" to surface, "foreground" to foreground,
        "primary" to primary, "secondary" to secondary, "accent" to accent,
    )

    fun with(name: String, hex: String): LookColors = when (name) {
        "background" -> copy(background = hex)
        "surface" -> copy(surface = hex)
        "foreground" -> copy(foreground = hex)
        "primary" -> copy(primary = hex)
        "secondary" -> copy(secondary = hex)
        "accent" -> copy(accent = hex)
        else -> this
    }

    companion object {
        val Default = LookColors("#14161f", "#20232f", "#e6e8f0", "#e0683c", "#f2b880", "#7fd1ae")
    }
}

/** The parts a look is made of. Each one is borrowed from one of the ten looks. */
enum class LookPart(val label: String, val hint: String) {
    Frame("Frame", "cards, menus, reader bars, Home blocks"),
    Bar("Tab bar", "the bar, its middle button, the extras menu"),
    Body("Text font", "running text"),
    Title("Heading font", "titles and headings"),
    Numbers("Number font", "episodes, chapters, times"),
    Corners("Corners", "panels, covers, buttons"),
    Motion("Movement", "presses, tab change, easing"),
    Words("Words", "notifications, empty lists, buttons"),
    Sounds("Sounds", "taps, tabs, finishing"),
    Haptics("Vibration", "the buzz's rhythm"),
}

/**
 * A look as data: which of the ten looks each part comes from, and optionally its own colours and background scene.
 * The ten looks are the presets, every part from the same look. A look of your own mixes them.
 */
@Serializable
data class LookSpec(
    val name: String,
    val frame: UiStyle,
    val bar: UiStyle = frame,
    val body: UiStyle = frame,
    val title: UiStyle = frame,
    val numbers: UiStyle = frame,
    val corners: UiStyle = frame,
    val motion: UiStyle = frame,
    val words: UiStyle = frame,
    val sounds: UiStyle = frame,
    val haptics: UiStyle = frame,
    /** Null: the theme chosen in Appearance. */
    val colors: LookColors? = null,
    /** Null: the scene chosen in Appearance. */
    val scene: SceneKind? = null,
) {
    operator fun get(part: LookPart): UiStyle = when (part) {
        LookPart.Frame -> frame
        LookPart.Bar -> bar
        LookPart.Body -> body
        LookPart.Title -> title
        LookPart.Numbers -> numbers
        LookPart.Corners -> corners
        LookPart.Motion -> motion
        LookPart.Words -> words
        LookPart.Sounds -> sounds
        LookPart.Haptics -> haptics
    }

    fun with(part: LookPart, style: UiStyle): LookSpec = when (part) {
        LookPart.Frame -> copy(frame = style)
        LookPart.Bar -> copy(bar = style)
        LookPart.Body -> copy(body = style)
        LookPart.Title -> copy(title = style)
        LookPart.Numbers -> copy(numbers = style)
        LookPart.Corners -> copy(corners = style)
        LookPart.Motion -> copy(motion = style)
        LookPart.Words -> copy(words = style)
        LookPart.Sounds -> copy(sounds = style)
        LookPart.Haptics -> copy(haptics = style)
    }

    /** Every part from [style], keeping the name, colours and scene. */
    fun allFrom(style: UiStyle): LookSpec = LookPart.entries.fold(this) { spec, part -> spec.with(part, style) }

    /** Whether this is just one of the ten looks. */
    val preset: UiStyle? get() = frame.takeIf { f -> LookPart.entries.all { get(it) == f } && colors == null && scene == null }

    /** A short line of what it borrows: "Bunko · words from Shin'ya, sounds from Eki". */
    fun summary(): String {
        fun words(parts: List<LookPart>) = parts.joinToString("/") { it.label.lowercase().removeSuffix(" font") }
        val rest = LookPart.entries.drop(1)
        // Most parts from one other look: name the odd ones out, then "the rest from" it.
        val most = rest.groupBy { get(it) }.maxByOrNull { it.value.size }?.takeIf { it.key != frame && it.value.size >= 4 }?.key
        val listed = rest.filter { get(it) != (most ?: frame) }
        val tail = (listed.groupBy { get(it) }.entries.map { (style, parts) -> words(parts) + " from " + style.label } +
            listOfNotNull(most?.let { "the rest from " + it.label })).joinToString(", ")
        val extra = listOfNotNull(if (colors != null) "own colours" else null, scene?.let { "scene: ${it.label}" })
        return (listOf(frame.label + " frame") + listOfNotNull(tail.takeIf { it.isNotEmpty() }) + extra).joinToString(" · ")
    }

    companion object {
        const val VERSION = 1
        const val EXTENSION = "aniliphy-look"

        fun preset(style: UiStyle): LookSpec = LookSpec(style.label, style)

        val Presets: List<LookSpec> = UiStyle.entries.map(::preset)

        private val json = Json { prettyPrint = true; encodeDefaults = true }

        /** The `.aniliphy-look` file: the spec and a version. */
        fun export(spec: LookSpec): String {
            val body = json.encodeToJsonElement(serializer(), spec).jsonObject
            return json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("version" to kotlinx.serialization.json.JsonPrimitive(VERSION)) + body))
        }

        /**
         * Reads a `.aniliphy-look` (or a saved one). Anything it doesn't know (a look from a newer version, a bad
         * colour) falls back: an unknown part to the frame, a bad colour to the default. Null when it isn't a look.
         */
        fun read(text: String): LookSpec? {
            val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            fun str(key: String) = runCatching { obj[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
            fun style(key: String) = str(key)?.let { s -> UiStyle.entries.firstOrNull { it.name.equals(s, true) } }
            if (obj["version"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() } == null && str("frame") == null) return null
            val frame = style("frame") ?: return null
            val colors = runCatching { obj["colors"]?.jsonObject }.getOrNull()?.let { c ->
                fun hex(key: String, fallback: String) = runCatching { c[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
                    ?.let(::cleanHex) ?: fallback
                val d = LookColors.Default
                LookColors(
                    hex("background", d.background), hex("surface", d.surface), hex("foreground", d.foreground),
                    hex("primary", d.primary), hex("secondary", d.secondary), hex("accent", d.accent),
                )
            }
            return LookSpec(
                name = str("name")?.trim()?.take(40)?.takeIf { it.isNotEmpty() } ?: "Imported look",
                frame = frame,
                bar = style("bar") ?: frame,
                body = style("body") ?: frame,
                title = style("title") ?: frame,
                numbers = style("numbers") ?: frame,
                corners = style("corners") ?: frame,
                motion = style("motion") ?: frame,
                words = style("words") ?: frame,
                sounds = style("sounds") ?: frame,
                haptics = style("haptics") ?: frame,
                colors = colors,
                scene = str("scene")?.let { s -> SceneKind.entries.firstOrNull { it.name.equals(s, true) } },
            )
        }

        /** "#rrggbb" in lower case, or null. */
        fun cleanHex(value: String): String? {
            val digits = value.trim().removePrefix("#")
            return if (digits.length == 6 && digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) "#" + digits.lowercase() else null
        }

        /** Saved looks of your own, as one JSON list. */
        fun readAll(text: String?): List<LookSpec> {
            if (text.isNullOrBlank()) return emptyList()
            val array = runCatching { Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray }.getOrNull() ?: return emptyList()
            return array.mapNotNull { read(it.toString()) }
        }

        fun writeAll(specs: List<LookSpec>): String =
            Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(serializer()), specs)
    }
}
