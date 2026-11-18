package com.aniliphy.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookSpecTest {
    @Test
    fun presetsAreEveryPartFromOneLook() {
        assertEquals(10, LookSpec.Presets.size)
        for (spec in LookSpec.Presets) {
            val style = spec.frame
            LookPart.entries.forEach { assertEquals(style, spec[it]) }
            assertEquals(style, spec.preset)
        }
        // Own colours or a scene make it a look of its own, even with every part the same.
        assertNull(LookSpec.preset(UiStyle.Bunko).copy(colors = LookColors.Default).preset)
        assertNull(LookSpec.preset(UiStyle.Bunko).copy(scene = SceneKind.Rain).preset)
    }

    @Test
    fun mixedPartsStayApart() {
        // The Dela case: a Modern frame with Tankōbon's text font.
        val mixed = LookSpec.preset(UiStyle.Modern).with(LookPart.Body, UiStyle.Tankobon).with(LookPart.Words, UiStyle.Jrpg)
        assertEquals(UiStyle.Modern, mixed.frame)
        assertEquals(UiStyle.Tankobon, mixed.body)
        assertEquals(UiStyle.Modern, mixed.title)
        assertEquals(UiStyle.Jrpg, mixed[LookPart.Words])
        assertNull(mixed.preset)
        assertEquals("Modern frame · text from Tankōbon, words from JRPG", mixed.summary())
        val mostly = LookSpec.preset(UiStyle.Genga).with(LookPart.Frame, UiStyle.Modern).with(LookPart.Body, UiStyle.Bunko).with(LookPart.Corners, UiStyle.Modern)
        assertEquals("Modern frame · text from Bunko, corners from Modern, the rest from Genga", mostly.summary())
        val all = mixed.allFrom(UiStyle.Eki)
        assertEquals(UiStyle.Eki, all.preset)
    }

    @Test
    fun fileRoundTrip() {
        val spec = LookSpec(
            "Night shelf", UiStyle.Bunko, words = UiStyle.Shinya, sounds = UiStyle.Eki, numbers = UiStyle.Jrpg,
            colors = LookColors.Default.with("primary", "#123abc"), scene = SceneKind.Fireflies,
        )
        val text = LookSpec.export(spec)
        assertTrue(text.contains("\"version\": 1"))
        assertEquals(spec, LookSpec.read(text))
        assertEquals(listOf(spec, LookSpec.preset(UiStyle.Glass)), LookSpec.readAll(LookSpec.writeAll(listOf(spec, LookSpec.preset(UiStyle.Glass)))))
    }

    @Test
    fun oddFilesFallBack() {
        // A newer look: an unknown part, an unknown look, a bad colour and an unknown scene.
        val newer = """
            {"version": 3, "name": "  From the future  ", "frame": "glass", "body": "Hologram", "sparkles": true,
             "colors": {"background": "#000000", "primary": "red", "accent": "#ABCDEF"}, "scene": "Meteors"}
        """
        val spec = LookSpec.read(newer)!!
        assertEquals("From the future", spec.name)
        assertEquals(UiStyle.Glass, spec.frame)
        assertEquals(UiStyle.Glass, spec.body)
        assertEquals("#000000", spec.colors!!.background)
        assertEquals(LookColors.Default.primary, spec.colors!!.primary)
        assertEquals("#abcdef", spec.colors!!.accent)
        assertEquals(LookColors.Default.surface, spec.colors!!.surface)
        assertNull(spec.scene)

        assertNull(LookSpec.read("not json"))
        assertNull(LookSpec.read("""{"version": 1, "frame": "Nothing"}"""))
        assertNull(LookSpec.read("""{"title": "a manga"}"""))
        assertNull(LookSpec.read("[1,2]"))
        assertEquals("Imported look", LookSpec.read("""{"version": 1, "frame": "Eki", "name": ""}""")!!.name)
        assertEquals(emptyList<LookSpec>(), LookSpec.readAll("{broken"))
        assertEquals(null, LookSpec.cleanHex("#12345"))
        assertEquals("#a1b2c3", LookSpec.cleanHex(" A1B2C3 "))
    }
}
