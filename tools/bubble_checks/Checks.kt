import com.comicreader.app.data.bubble.softenMaskBoundary
import com.comicreader.app.ui.reader.bubbleMagnification
import kotlin.math.abs

fun main() {
    fun scale(text: Float, target: Float = 14f) =
        bubbleMagnification(50f, 30f, text, target, 360f, 720f)
    check(abs(scale(7f) - 2f) < 0.001f)
    check(scale(14f) == 1f) // Already readable text must not become a giant overlay.
    check(scale(4f) == 3f)
    check(scale(7f, 18f) > scale(7f)) // Honor the user's font scale.
    check(scale(0f).isFinite() && scale(Float.NaN).isFinite())
    for (w in listOf(30f, 150f, 400f)) for (h in listOf(20f, 300f, 900f)) {
        val s = bubbleMagnification(w, h, 5f, 14f, 360f, 720f)
        check(w * s <= 360f * 0.86f + 0.01f)
        check(h * s <= 720f * 0.48f + 0.01f)
        check(w * h * s * s <= 360f * 720f * 0.22f + 0.1f)
    }
    val mask = IntArray(81)
    for (y in 2..6) for (x in 2..6) mask[y * 9 + x] = 255
    mask[1 * 9 + 4] = 255 // Thin tail.
    mask[4 * 9 + 7] = 100 // Native translucent edge.
    val original = mask.copyOf()
    val result = softenMaskBoundary(mask, 9, 9)
    check(mask.contentEquals(original))
    check(result[4 * 9 + 4] == 255) // Text surface remains solid.
    check(result[2 * 9 + 2] in 128..254) // Hard corner is antialiased.
    check(result[1 * 9 + 4] >= 128) // Tail remains visible.
    check(result[4 * 9 + 7] == 100)
    check(result.indices.all { mask[it] != 0 || result[it] == 0 })
    check(result.indices.all { result[it] in 0..mask[it] })
    println("Bubble sizing and mask boundary regression checks passed")
}
