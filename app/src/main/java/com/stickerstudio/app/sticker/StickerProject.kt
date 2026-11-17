package com.stickerstudio.app.sticker

/**
 * The complete, serialisable description of how a static sticker is built from its source
 * bitmap. It is stored with every saved sticker so the sticker can be re-opened and edited
 * later, and it is the single input of [StickerRenderer].
 *
 * Coordinates are in the 512x512 sticker space. Colours are ARGB ints.
 */
object Colors {
    const val WHITE: Int = -1               // 0xFFFFFFFF
    const val BLACK: Int = -16777216        // 0xFF000000
}

enum class MaskKind { NONE, CIRCLE, ROUNDED }
enum class LayerKind { TEXT, SHAPE, IMAGE }
enum class ShapeKind { CIRCLE, RECT, STAR, HEART, LINE, ARROW }

/** Crop rectangle as fractions (0..1) of the source image: left, top, right, bottom. */
data class CropRect(
    val l: Float = 0f,
    val t: Float = 0f,
    val r: Float = 1f,
    val b: Float = 1f,
) {
    val isFull: Boolean get() = l <= 0.001f && t <= 0.001f && r >= 0.999f && b >= 0.999f
}

data class Xform(
    val scale: Float = 1f,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val rotation: Float = 0f,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
)

data class OutlineSpec(
    val enabled: Boolean = false,
    val color: Int = Colors.WHITE,
    val width: Float = 8f,
)

/** Freehand pen stroke. [points] = x0,y0,x1,y1,... */
data class PenStroke(val color: Int, val width: Float, val points: List<Float>, val alpha: Float = 1f)

/** Eraser (or "restore") brush stroke applied on the transformed image. */
data class EraseStroke(val restore: Boolean, val radius: Float, val points: List<Float>)

/** A text / emoji layer or a shape layer. [size] is the font size (text) or shape size (shape). */
data class Layer(
    val id: String,
    val kind: LayerKind,
    val text: String = "",
    val color: Int = Colors.WHITE,
    val size: Float = 72f,
    val bold: Boolean = true,
    val italic: Boolean = false,
    val font: Int = 0,
    val strokeColor: Int = Colors.BLACK,
    val strokeWidth: Float = 0f,
    val shadow: Boolean = false,
    val shape: ShapeKind = ShapeKind.CIRCLE,
    val x: Float = 256f,
    val y: Float = 256f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    /** Layer panel data: custom name ("" = automatic), visibility, lock. */
    val name: String = "",
    val visible: Boolean = true,
    val locked: Boolean = false,
    val opacity: Float = 1f,
    /** Text only: 0 = left, 1 = centre, 2 = right. */
    val align: Int = 1,
    /** Text only: extra space between letters in em (ignored for Arabic so letters stay joined). */
    val letterSpacing: Float = 0f,
    /** Text only: line height as a multiple of the font size. */
    val lineSpacing: Float = 1.2f,
    val bgEnabled: Boolean = false,
    val bgColor: Int = Colors.BLACK,
    /** Shape only: draw just the outline (in [color]) instead of a filled shape. Lines and arrows are always outlines. */
    val hollow: Boolean = false,
    /** Image layer only: file name inside the app's sources folder. */
    val imageFile: String = "",
)

/**
 * Non-destructive picture adjustments, applied when rendering, so changing them again and again
 * never lowers quality. All values are 0 by default (= untouched picture).
 */
data class Adjust(
    val brightness: Float = 0f,   // -1..1
    val contrast: Float = 0f,     // -1..1
    val saturation: Float = 0f,   // -1..1
    val warmth: Float = 0f,       // -1 (cool) .. 1 (warm)
    val exposure: Float = 0f,     // -1..1
    val blur: Float = 0f,         // 0..1
    val sharpen: Float = 0f,      // 0..1
    val grayscale: Boolean = false,
    val sepia: Boolean = false,
) {
    val isDefault: Boolean get() = this == Adjust()
}

data class StickerProject(
    val xform: Xform = Xform(),
    val opacity: Float = 1f,
    val mask: MaskKind = MaskKind.NONE,
    val erase: List<EraseStroke> = emptyList(),
    val outline: OutlineSpec = OutlineSpec(),
    val shadow: Boolean = false,
    val layers: List<Layer> = emptyList(),
    val pen: List<PenStroke> = emptyList(),
    val crop: CropRect = CropRect(),
    val adjust: Adjust = Adjust(),
)

/** Settings of an animated sticker (GIF / video source). */
data class AnimatedSettings(
    val startMs: Long = 0L,
    val durationMs: Long = 3000L,
    val fps: Int = 12,
    val cover: Boolean = false,
    val caption: String = "",
    val crop: CropRect = CropRect(),
    /** Hand-edited timeline (delete / duplicate / reorder / per-frame time). null = even steps from the settings above. */
    val frames: List<TimelineFrame>? = null,
)
