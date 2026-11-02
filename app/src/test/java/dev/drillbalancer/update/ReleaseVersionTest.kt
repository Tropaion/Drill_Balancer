package dev.drillbalancer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test
    fun parsesPlainAndTagForms() {
        assertEquals(ReleaseVersion(0, 2, 0), ReleaseVersion.parse("0.2.0"))
        assertEquals(ReleaseVersion(0, 2, 0), ReleaseVersion.parse("v0.2.0"))
    }

    @Test
    fun comparesNumbersNotStrings() {
        assertTrue(ReleaseVersion.parse("0.10.0")!! > ReleaseVersion.parse("0.9.0")!!)
        assertTrue(ReleaseVersion.parse("1.0.0")!! > ReleaseVersion.parse("0.99.99")!!)
        assertTrue(ReleaseVersion.parse("0.2.1")!! > ReleaseVersion.parse("0.2.0")!!)
    }

    @Test
    fun printsWithoutThePrefix() {
        assertEquals("0.2.0", ReleaseVersion.parse("v0.2.0").toString())
    }

    @Test
    fun anythingElseIsNoVersion() {
        for (text in listOf(null, "", "1.2", "1.2.3.4", "1.2.x", "0.2.0-rc1", "dataset-v1", "99999999999.0.0")) {
            assertNull(text, ReleaseVersion.parse(text))
        }
    }
}
