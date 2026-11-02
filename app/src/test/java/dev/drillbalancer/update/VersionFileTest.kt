package dev.drillbalancer.update

import dev.drillbalancer.BuildConfig
import dev.drillbalancer.protocol.FirmwareSources
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The app's version comes from the one VERSION file at the repository root. */
class VersionFileTest {
    private val version = File(FirmwareSources.dir, "../VERSION").readText().trim()

    @Test
    fun versionNameIsTheFile() {
        assertEquals(version, BuildConfig.VERSION_NAME)
    }

    @Test
    fun versionCodeFollowsTheFormula() {
        val (major, minor, patch) = version.split('.').map(String::toInt)
        assertEquals(major * 10000 + minor * 100 + patch, BuildConfig.VERSION_CODE)
    }
}
