package dev.drillbalancer.ui

import dev.drillbalancer.protocol.LevelStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun wallArrowFollowsTheElevation() {
        assertEquals(1, wallArrow(1.4f, LevelStatus.NEAR))
        assertEquals(-1, wallArrow(-1.4f, LevelStatus.NEAR))
        assertEquals(0, wallArrow(1.4f, LevelStatus.ON_TARGET))
        assertEquals(0, wallArrow(0.04f, LevelStatus.NEAR))   // reads 0.0
    }

    @Test
    fun invertedWallArrowShowsTheWayToCorrect() {
        assertEquals(-1, wallArrow(dotDeg(1.4f, invert = true), LevelStatus.NEAR))
        assertEquals(1, wallArrow(dotDeg(-1.4f, invert = true), LevelStatus.NEAR))
        assertEquals(1, wallArrow(dotDeg(1.4f, invert = false), LevelStatus.NEAR))
        assertEquals(0, wallArrow(dotDeg(1.4f, invert = true), LevelStatus.ON_TARGET))
    }

    @Test
    fun yawSaysRightOrLeft() {
        assertEquals("R 2.1°", formatYaw(2.1f))
        assertEquals("L 2.1°", formatYaw(-2.1f))
        assertEquals("0.0°", formatYaw(0.04f))
    }

    @Test
    fun invertedYawSaysWhichWayToTurn() {
        assertEquals("L 2.1°", formatYaw(dotDeg(2.1f, invert = true)))
        assertEquals("R 2.1°", formatYaw(dotDeg(-2.1f, invert = true)))
        assertEquals("0.0°", formatYaw(dotDeg(-0.04f, invert = true)))
    }
}
