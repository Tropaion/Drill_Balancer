package dev.drillbalancer.ui.training.review

import androidx.compose.runtime.Immutable
import kotlin.math.floor

/**
 * Where the marker handles go above the plot: glove-sized handles that never cover each other.
 *
 * The view's width is cut into columns one handle and a gap wide. A column with at most
 * maxRows markers shows each with its own handle, one row below the other in time order, the
 * same whichever is selected (a second tap on a handle finds the same marker there); one with
 * more shows a count handle for them (a tap zooms in on them), the selected marker keeping its
 * own handle in the first row and the count in the second. A dragged marker keeps the row its
 * handle had when the drag began (pin) wherever the drag takes it, the other markers of its
 * column in the other rows: no handle changes row under the finger. Then the handles of each row
 * move as little as they can from their markers (least squares) so that each is a gap clear of
 * the next and all stay in the view: with at most one handle per column in a row, they always fit.
 */
internal object MarkerLayout {
    const val MAX_ROWS = 2

    // immutable (the lists are never changed): an unchanged item skips its handle's recomposition
    @Immutable
    sealed interface Item {
        val left: Float
        val row: Int
        val markers: List<MarkerView>

        /** The handle's identity in the plot, the same wherever the layout puts it. */
        val key: String
    }

    /** One marker's handle. */
    @Immutable
    data class Handle(val marker: MarkerView, override val left: Float, override val row: Int) : Item {
        override val markers: List<MarkerView> get() = listOf(marker)
        override val key: String get() = marker.id
    }

    /** A count handle for [markers] (in time order), too many for their column. */
    @Immutable
    data class Cluster(override val markers: List<MarkerView>, override val left: Float, override val row: Int) : Item {
        override val key: String get() = "count:" + markers.first().id
        val t0Us: Long get() = markers.first().tUs
        val t1Us: Long get() = markers.last().tUs
    }

    /**
     * The handles of the [markers] in [fromUs, toUs) on a view [widthPx] wide, handles
     * [handlePx] square with [gapPx] between them; [selected] always has its own handle.
     * [maxRows] (at least 2): the rows a column may stack before its markers are counted.
     * [pin]: a marker (being dragged) and the row its handle keeps (below [maxRows]).
     */
    fun layout(
        markers: List<MarkerView>,
        fromUs: Long,
        toUs: Long,
        widthPx: Float,
        handlePx: Float,
        gapPx: Float,
        selected: String? = null,
        maxRows: Int = MAX_ROWS,
        pin: Pair<String, Int>? = null,
    ): List<Item> {
        require(maxRows >= 2) { "maxRows $maxRows: the selected marker and a count need two rows" }
        val span = (toUs - fromUs).coerceAtLeast(1).toDouble()
        val visible = markers.filter { it.tUs in fromUs until toUs }.sortedBy { it.tUs }
        if (visible.isEmpty() || widthPx <= 0f) return emptyList()
        val pitch = handlePx + gapPx
        val columns = maxOf(1, floor((widthPx + gapPx) / pitch).toInt())
        fun x(t: Long) = ((t - fromUs) / span * widthPx).toFloat()

        // (the item with row and the centre it wants), column by column
        val wanted = ArrayList<Pair<Item, Float>>()
        for ((_, ms) in visible.groupBy { (x(it.tUs) / widthPx * columns).toInt().coerceIn(0, columns - 1) }.toSortedMap()) {
            val pinned = pin?.let { (id, r) -> ms.firstOrNull { it.id == id }?.let { it to r.coerceIn(0, maxRows - 1) } }
            if (ms.size <= maxRows) {
                // time order; a pinned marker in its row, the others in the rows left
                val free = (0 until ms.size + 1).filter { it != pinned?.second }.iterator()
                for (m in ms) wanted += Handle(m, 0f, if (m === pinned?.first) pinned.second else free.next()) to x(m.tUs)
                continue
            }
            val sel = ms.firstOrNull { it.id == selected }
            val selRow = if (sel != null && sel === pinned?.first) pinned.second else 0
            if (sel != null) wanted += Handle(sel, 0f, selRow) to x(sel.tUs)
            val rest = ms.filter { it !== sel }
            wanted += Cluster(rest, 0f, if (sel == null) 0 else if (selRow == 0) 1 else 0) to (x(rest.first().tUs) + x(rest.last().tUs)) / 2
        }
        val out = ArrayList<Item>(wanted.size)
        for ((_, row) in wanted.groupBy { it.first.row }) {
            val lefts = spread(row.map { it.second - handlePx / 2 }, pitch, widthPx - handlePx)
            row.forEachIndexed { i, (item, _) ->
                out += when (item) {
                    is Handle -> item.copy(left = lefts[i])
                    is Cluster -> item.copy(left = lefts[i])
                }
            }
        }
        return out.sortedWith(compareBy<Item> { it.row }.thenBy { it.left })
    }

    /**
     * The positions nearest (least squares) to [want] (ascending) with each at least [pitch]
     * after the one before and all in [0, maxLeft]: with z_i = y_i - i * pitch the order
     * constraint is z non-decreasing (pool adjacent violators), the bounds the same for every z.
     */
    fun spread(want: List<Float>, pitch: Float, maxLeft: Float): List<Float> {
        val n = want.size
        val hi = maxOf(0.0, maxLeft.toDouble() - (n - 1) * pitch.toDouble())
        val sums = DoubleArray(n)
        val counts = IntArray(n)
        var blocks = 0
        for (i in 0 until n) {
            sums[blocks] = want[i].toDouble() - i * pitch.toDouble()
            counts[blocks] = 1
            blocks++
            while (blocks > 1 && sums[blocks - 2] / counts[blocks - 2] > sums[blocks - 1] / counts[blocks - 1]) {
                sums[blocks - 2] += sums[blocks - 1]
                counts[blocks - 2] += counts[blocks - 1]
                blocks--
            }
        }
        val out = ArrayList<Float>(n)
        for (b in 0 until blocks) {
            val z = (sums[b] / counts[b]).coerceIn(0.0, hi)
            repeat(counts[b]) { out += (z + out.size * pitch.toDouble()).toFloat() }
        }
        return out
    }
}
