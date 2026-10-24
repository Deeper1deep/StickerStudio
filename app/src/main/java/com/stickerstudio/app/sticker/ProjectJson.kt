package com.stickerstudio.app.sticker

import com.stickerstudio.app.core.JsonParseException
import com.stickerstudio.app.core.MiniJson
import com.stickerstudio.app.core.asArray
import com.stickerstudio.app.core.asObject
import com.stickerstudio.app.core.optBool
import com.stickerstudio.app.core.optFloat
import com.stickerstudio.app.core.optInt
import com.stickerstudio.app.core.optLong
import com.stickerstudio.app.core.optString

/**
 * (De)serialisation of [StickerProject] / [AnimatedSettings]. Missing keys fall back to
 * defaults, so projects saved by an older app version keep opening after an update.
 */
object ProjectJson {

    private const val VERSION = 1

    // ------------------------------------------------------------------ encode

    fun encode(p: StickerProject): String = MiniJson.stringify(
        linkedMapOf<String, Any?>(
            "v" to VERSION,
            "xform" to linkedMapOf<String, Any?>(
                "scale" to p.xform.scale, "dx" to p.xform.dx, "dy" to p.xform.dy,
                "rotation" to p.xform.rotation, "flipX" to p.xform.flipX, "flipY" to p.xform.flipY,
            ),
            "opacity" to p.opacity,
            "mask" to p.mask.name,
            "erase" to p.erase.map {
                linkedMapOf<String, Any?>("restore" to it.restore, "radius" to it.radius, "points" to it.points)
            },
            "outline" to linkedMapOf<String, Any?>(
                "enabled" to p.outline.enabled, "color" to p.outline.color, "width" to p.outline.width,
            ),
            "shadow" to p.shadow,
            "layers" to p.layers.map { l ->
                linkedMapOf<String, Any?>(
                    "id" to l.id, "kind" to l.kind.name, "text" to l.text, "color" to l.color,
                    "size" to l.size, "bold" to l.bold, "italic" to l.italic, "font" to l.font,
                    "strokeColor" to l.strokeColor, "strokeWidth" to l.strokeWidth, "shadow" to l.shadow,
                    "shape" to l.shape.name, "x" to l.x, "y" to l.y, "scale" to l.scale, "rotation" to l.rotation,
                )
            },
            "pen" to p.pen.map {
                linkedMapOf<String, Any?>("color" to it.color, "width" to it.width, "points" to it.points)
            },
        )
    )

    fun encode(a: AnimatedSettings): String = MiniJson.stringify(
        linkedMapOf<String, Any?>(
            "v" to VERSION, "startMs" to a.startMs, "durationMs" to a.durationMs,
            "fps" to a.fps, "cover" to a.cover, "caption" to a.caption,
        )
    )

    // ------------------------------------------------------------------ decode

    /** Returns null if [json] is not valid; callers then start from a default project. */
    fun decodeProject(json: String?): StickerProject? {
        if (json.isNullOrBlank()) return null
        val root = try {
            MiniJson.parse(json).asObject()
        } catch (e: JsonParseException) {
            null
        } ?: return null

        val x = root["xform"].asObject() ?: emptyMap()
        val o = root["outline"].asObject() ?: emptyMap()
        val def = StickerProject()

        return StickerProject(
            xform = Xform(
                scale = x.optFloat("scale", 1f).coerceIn(0.05f, 20f),
                dx = x.optFloat("dx", 0f), dy = x.optFloat("dy", 0f),
                rotation = x.optFloat("rotation", 0f),
                flipX = x.optBool("flipX", false), flipY = x.optBool("flipY", false),
            ),
            opacity = root.optFloat("opacity", 1f).coerceIn(0f, 1f),
            mask = enumOr(root.optString("mask"), MaskKind.NONE),
            erase = root["erase"].asArray().orEmpty().mapNotNull { e ->
                val m = e.asObject() ?: return@mapNotNull null
                EraseStroke(m.optBool("restore", false), m.optFloat("radius", 24f), floats(m["points"]))
            },
            outline = OutlineSpec(
                enabled = o.optBool("enabled", def.outline.enabled),
                color = o.optInt("color", def.outline.color),
                width = o.optFloat("width", def.outline.width),
            ),
            shadow = root.optBool("shadow", false),
            layers = root["layers"].asArray().orEmpty().mapNotNull { e ->
                val m = e.asObject() ?: return@mapNotNull null
                Layer(
                    id = m.optString("id") ?: return@mapNotNull null,
                    kind = enumOr(m.optString("kind"), LayerKind.TEXT),
                    text = m.optString("text") ?: "",
                    color = m.optInt("color", Colors.WHITE),
                    size = m.optFloat("size", 72f),
                    bold = m.optBool("bold", true),
                    italic = m.optBool("italic", false),
                    font = m.optInt("font", 0),
                    strokeColor = m.optInt("strokeColor", Colors.BLACK),
                    strokeWidth = m.optFloat("strokeWidth", 0f),
                    shadow = m.optBool("shadow", false),
                    shape = enumOr(m.optString("shape"), ShapeKind.CIRCLE),
                    x = m.optFloat("x", 256f), y = m.optFloat("y", 256f),
                    scale = m.optFloat("scale", 1f), rotation = m.optFloat("rotation", 0f),
                )
            },
            pen = root["pen"].asArray().orEmpty().mapNotNull { e ->
                val m = e.asObject() ?: return@mapNotNull null
                PenStroke(m.optInt("color", Colors.BLACK), m.optFloat("width", 8f), floats(m["points"]))
            },
        )
    }

    fun decodeAnimated(json: String?): AnimatedSettings? {
        if (json.isNullOrBlank()) return null
        val m = try {
            MiniJson.parse(json).asObject()
        } catch (e: JsonParseException) {
            null
        } ?: return null
        return AnimatedSettings(
            startMs = m.optLong("startMs", 0L).coerceAtLeast(0L),
            durationMs = m.optLong("durationMs", 3000L).coerceIn(500L, 10_000L),
            fps = m.optInt("fps", 12).coerceIn(5, 30),
            cover = m.optBool("cover", false),
            caption = m.optString("caption") ?: "",
        )
    }

    private fun floats(v: Any?): List<Float> =
        v.asArray().orEmpty().mapNotNull { (it as? Number)?.toFloat() }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}
