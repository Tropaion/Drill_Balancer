package dev.drillbalancer.ui.training.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** MarkerLayout: handles that never cover each other, counted where the markers crowd. */
class MarkerLayoutTest {
    // the phone of the screenshots: 360 dp at xhdpi less the 16 dp margins, 56 dp handles, 4 dp gaps
    private val width = 984f
    private val handle = 168f
    private val gap = 12f

    private fun m(id: String, type: String, tUs: Long, label: String = id) = MarkerView(id, type, tUs, label, needsConfirm = false, user = false, edited = false)

    /** A take like the emulator's 80do: five holes through (ON, C, BT, OFF) in 50 s. */
    private val dense = (0 until 5).flatMap { h ->
        val t = 1_000_000L + h * 9_800_000L
        listOf(
            m("on$h", "motor_on", t, "ON"), m("c$h", "contact", t + 1_500_000, "C${h + 1}"),
            m("bt$h", "breakthrough", t + 5_200_000, "BT${h + 1}"), m("off$h", "motor_off", t + 6_100_000, "OFF"),
        )
    }

    /** No two handles of a row closer than a handle and a gap, all in the view, every marker once, at most [maxRows] rows. */
    private fun assertLaidOut(markers: List<MarkerView>, items: List<MarkerLayout.Item>, from: Long, to: Long, w: Float = width, maxRows: Int = MarkerLayout.MAX_ROWS) {
        for ((row, its) in items.groupBy { it.row }) {
            val lefts = its.map { it.left }.sorted()
            for (i in 1 until lefts.size) assertTrue("row $row: ${lefts[i - 1]} and ${lefts[i]} overlap", lefts[i] - lefts[i - 1] >= handle + gap - 0.01f)
            for (l in lefts) assertTrue("row $row: $l outside the view", l >= -0.01f && l <= maxOf(0f, w - handle) + 0.01f)
        }
        assertEquals(markers.filter { it.tUs in from until to }.map { it.id }.sorted(), items.flatMap { it.markers }.map { it.id }.sorted())
        assertTrue(items.all { it.row < maxRows })
    }

    @Test
    fun aDenseTakeAtFullZoomIsCountedNotOverlapped() {
        val items = MarkerLayout.layout(dense, 0, 50_000_000, width, handle, gap)
        assertLaidOut(dense, items, 0, 50_000_000)
        assertTrue("crowded columns are counted", items.any { it is MarkerLayout.Cluster })
        // a count handle sits over its markers
        for (c in items.filterIsInstance<MarkerLayout.Cluster>()) {
            val mid = ((c.t0Us + c.t1Us) / 2) / 50_000_000.0 * width
            assertTrue(abs(c.left + handle / 2 - mid) < handle + gap)
        }
    }

    @Test
    fun sparseMarkersGetAHandleEachOverTheirLine() {
        val ms = listOf(m("a", "motor_on", 1_000_000), m("b", "contact", 4_000_000), m("c", "breakthrough", 7_000_000), m("d", "motor_off", 9_900_000))
        val items = MarkerLayout.layout(ms, 0, 10_000_000, width, handle, gap)
        assertLaidOut(ms, items, 0, 10_000_000)
        assertTrue(items.all { it is MarkerLayout.Handle && it.row == 0 })
        for (h in items) {
            val x = h.markers.single().tUs / 10_000_000.0 * width
            val want = (x - handle / 2).coerceIn(0.0, (width - handle).toDouble())
            assertEquals(want, h.left.toDouble(), 0.5)
        }
    }

    @Test
    fun closeMarkersStackInRows() {
        val ms = listOf(m("a", "contact", 5_000_000), m("b", "breakthrough", 5_050_000))
        val items = MarkerLayout.layout(ms, 0, 10_000_000, width, handle, gap)
        assertEquals(listOf("a" to 0, "b" to 1), items.map { it.markers.single().id to it.row })
        // in time order whichever is selected
        for (sel in listOf("a", "b")) {
            assertEquals(items.map { it.markers.single().id to it.row }, MarkerLayout.layout(ms, 0, 10_000_000, width, handle, gap, selected = sel).map { it.markers.single().id to it.row })
        }
    }

    /** The marker whose handle is under [x] in [row], or null. */
    private fun at(items: List<MarkerLayout.Item>, x: Float, row: Int): String? =
        items.singleOrNull { it.row == row && x >= it.left && x < it.left + handle }?.let { (it as MarkerLayout.Handle).marker.id }

    /** A tap on the second row's handle selects it; a second tap on the same spot finds the same marker there. */
    @Test
    fun aSecondTapOnAHandleFindsTheSameMarker() {
        val ms = listOf(m("a", "contact", 5_000_000), m("b", "breakthrough", 5_050_000), m("c", "motor_off", 9_000_000))
        val before = MarkerLayout.layout(ms, 0, 10_000_000, width, handle, gap)
        for (h in before.filterIsInstance<MarkerLayout.Handle>()) {
            val x = h.left + handle / 2
            val after = MarkerLayout.layout(ms, 0, 10_000_000, width, handle, gap, selected = h.marker.id)
            assertEquals(h.marker.id, at(after, x, h.row))
        }
    }

    /** A dragged handle keeps the row it began in: past its neighbour, alone in a column, into a crowded one. */
    @Test
    fun aDragKeepsItsRow() {
        val a = m("a", "contact", 5_000_000)
        val c = m("c", "motor_off", 9_000_000)
        val b = m("b", "breakthrough", 5_050_000)
        val start = MarkerLayout.layout(listOf(a, b, c), 0, 10_000_000, width, handle, gap, selected = "b")
        val row = start.single { it is MarkerLayout.Handle && it.marker.id == "b" }.row
        assertEquals(1, row)
        fun rowOf(items: List<MarkerLayout.Item>, id: String) = items.single { it is MarkerLayout.Handle && it.marker.id == id }.row
        for (tUs in listOf(5_040_000L, 4_990_000L, 4_900_000L, 2_000_000L, 9_010_000L)) {
            val moved = listOf(a, b.copy(tUs = tUs), c)
            val items = MarkerLayout.layout(moved, 0, 10_000_000, width, handle, gap, selected = "b", pin = "b" to row)
            assertLaidOut(moved, items, 0, 10_000_000)
            assertEquals("b at $tUs", row, rowOf(items, "b"))
        }
        // past a: a takes the other row; let go, the rows are in time order again
        val past = listOf(a, b.copy(tUs = 4_990_000), c)
        assertEquals(0, rowOf(MarkerLayout.layout(past, 0, 10_000_000, width, handle, gap, selected = "b", pin = "b" to 1), "a"))
        assertEquals(0, rowOf(MarkerLayout.layout(past, 0, 10_000_000, width, handle, gap, selected = "b"), "b"))
        // into a column counted: its own handle in its row, the count in the other
        val crowd = (0 until 4).map { i -> m("x$i", "contact", 7_000_000L + i * 10_000) }
        val into = listOf(a, b.copy(tUs = 7_015_000)) + crowd
        val items = MarkerLayout.layout(into, 0, 10_000_000, width, handle, gap, selected = "b", pin = "b" to 1)
        assertLaidOut(into, items, 0, 10_000_000)
        assertEquals(1, rowOf(items, "b"))
        assertEquals(0, items.filterIsInstance<MarkerLayout.Cluster>().single { c2 -> c2.markers.any { it.id == "x0" } }.row)
    }

    @Test
    fun theSelectedMarkerKeepsItsHandle() {
        val full = MarkerLayout.layout(dense, 0, 50_000_000, width, handle, gap)
        val counted = full.filterIsInstance<MarkerLayout.Cluster>().first()
        val sel = counted.markers[1]
        val items = MarkerLayout.layout(dense, 0, 50_000_000, width, handle, gap, selected = sel.id)
        assertLaidOut(dense, items, 0, 50_000_000)
        assertEquals(0, items.single { it is MarkerLayout.Handle && it.marker.id == sel.id }.row)
        val rest = items.filterIsInstance<MarkerLayout.Cluster>().single { c -> c.markers.any { it.id == counted.markers[0].id } }
        assertEquals(1, rest.row)
        assertTrue(rest.markers.none { it.id == sel.id })
    }

    /** A count handle's zoom (ReviewViewModel.zoomTo: its markers' range and half of it again) spreads them into handles. */
    @Test
    fun zoomingInOnACountGivesTheMarkersTheirHandles() {
        for (c in MarkerLayout.layout(dense, 0, 50_000_000, width, handle, gap).filterIsInstance<MarkerLayout.Cluster>()) {
            val span = maxOf(ReviewViewModel.MIN_SPAN_US, (c.t1Us - c.t0Us) * 3 / 2)
            val from = (c.t0Us + c.t1Us) / 2 - span / 2
            val items = MarkerLayout.layout(dense, from, from + span, width, handle, gap)
            val ids = c.markers.map { it.id }.toSet()
            assertTrue(items.filter { it.markers.any { m -> m.id in ids } }.all { it is MarkerLayout.Handle })
        }
    }

    @Test
    fun randomTakesNeverOverlap() {
        val rnd = Random(8)
        repeat(500) { n ->
            val w = listOf(984f, 600f, 300f, 170f, 2000f)[n % 5]
            val ms = List(rnd.nextInt(0, 40)) { i -> m("m$i", "contact", rnd.nextLong(0, 10_000_000)) }
            val sel = ms.randomOrNull(rnd)?.id
            val items = MarkerLayout.layout(ms, 0, 10_000_000, w, handle, gap, selected = sel)
            assertLaidOut(ms, items, 0, 10_000_000, w)
            if (sel != null) assertTrue(items.any { it is MarkerLayout.Handle && it.marker.id == sel })
        }
    }

    /** Without the counts (the shortest view): every marker keeps a handle, in as many rows as it takes. */
    @Test
    fun withoutALimitEveryMarkerHasAHandle() {
        val ms = List(10) { i -> m("m$i", "contact", 100_000L + i * 1_000) }
        val items = MarkerLayout.layout(ms, 0, 200_000, width, handle, gap, maxRows = Int.MAX_VALUE)
        assertLaidOut(ms, items, 0, 200_000, maxRows = Int.MAX_VALUE)
        assertTrue(items.all { it is MarkerLayout.Handle })
    }

    @Test
    fun spreadIsTheLeastSquaresPlacement() {
        assertEquals(listOf(46f, 56f), MarkerLayout.spread(listOf(50f, 52f), 10f, 200f))      // both move by 4
        assertEquals(listOf(0f, 10f), MarkerLayout.spread(listOf(0f, 0f), 10f, 100f))         // not out of the view
        assertEquals(listOf(80f, 90f, 100f), MarkerLayout.spread(listOf(100f, 100f, 100f), 10f, 100f))
        assertEquals(listOf(5f, 50f), MarkerLayout.spread(listOf(5f, 50f), 10f, 100f))        // apart already: as they are
    }
}
