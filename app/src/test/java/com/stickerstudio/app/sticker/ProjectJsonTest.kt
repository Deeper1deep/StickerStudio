package com.stickerstudio.app.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectJsonTest {

    private val full = StickerProject(
        xform = Xform(scale = 1.8f, dx = 12.5f, dy = -30f, rotation = 15f, flipX = true, flipY = false),
        opacity = 0.75f,
        mask = MaskKind.ROUNDED,
        erase = listOf(
            EraseStroke(restore = false, radius = 20f, points = listOf(1f, 2f, 3f, 4f)),
            EraseStroke(restore = true, radius = 10f, points = listOf(5f, 6f)),
        ),
        outline = OutlineSpec(enabled = true, color = -65536, width = 14f),
        shadow = true,
        layers = listOf(
            Layer(id = "t1", kind = LayerKind.TEXT, text = "مرحبا 😀\nHello", color = Colors.WHITE, size = 90f,
                bold = false, italic = true, font = 2, strokeColor = Colors.BLACK, strokeWidth = 6f, shadow = true,
                x = 100f, y = 300f, scale = 1.2f, rotation = -20f),
            Layer(id = "s1", kind = LayerKind.SHAPE, shape = ShapeKind.HEART, color = -16711936, size = 140f),
        ),
        pen = listOf(PenStroke(color = -256, width = 9f, points = listOf(10f, 10f, 20f, 25f))),
    )

    @Test
    fun projectSurvivesRoundTrip() {
        val back = ProjectJson.decodeProject(ProjectJson.encode(full))
        assertNotNull(back)
        assertEquals(full, back)
    }

    @Test
    fun animatedSettingsSurviveRoundTrip() {
        val a = AnimatedSettings(startMs = 1500, durationMs = 4000, fps = 15, cover = true, caption = "تعليق")
        assertEquals(a, ProjectJson.decodeAnimated(ProjectJson.encode(a)))
    }

    @Test
    fun garbageAndEmptyAreRejected() {
        assertNull(ProjectJson.decodeProject(null))
        assertNull(ProjectJson.decodeProject(""))
        assertNull(ProjectJson.decodeProject("not json"))
        assertNull(ProjectJson.decodeProject("[1,2,3]"))
        assertNull(ProjectJson.decodeAnimated("{"))
    }

    @Test
    fun missingKeysFallBackToDefaults() {
        // A project written by an older/minimal version of the app.
        val p = ProjectJson.decodeProject("""{"v":1,"opacity":0.5}""")!!
        assertEquals(0.5f, p.opacity, 0.0001f)
        assertEquals(StickerProject().xform, p.xform)
        assertEquals(MaskKind.NONE, p.mask)
        assertEquals(emptyList<Layer>(), p.layers)
    }

    @Test
    fun unknownEnumValuesDoNotCrash() {
        val p = ProjectJson.decodeProject("""{"mask":"HEXAGON","layers":[{"id":"x","kind":"WIDGET","shape":"BLOB"}]}""")!!
        assertEquals(MaskKind.NONE, p.mask)
        assertEquals(LayerKind.TEXT, p.layers.single().kind)
        assertEquals(ShapeKind.CIRCLE, p.layers.single().shape)
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        val p = ProjectJson.decodeProject("""{"opacity":7,"xform":{"scale":-4}}""")!!
        assertEquals(1f, p.opacity, 0.0001f)
        assertEquals(0.05f, p.xform.scale, 0.0001f)
    }
}
