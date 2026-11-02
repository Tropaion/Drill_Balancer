package dev.drillbalancer.update

/** MAJOR.MINOR.PATCH of a release, a firmware or the app; compared as numbers, never as text. */
data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val FORMAT = Regex("""v?(\d+)\.(\d+)\.(\d+)""")

        /** "0.2.0" or the tag "v0.2.0"; null for anything else. */
        fun parse(text: String?): ReleaseVersion? {
            val parts = FORMAT.matchEntire(text ?: return null)?.groupValues?.drop(1) ?: return null
            val (major, minor, patch) = parts.map { it.toIntOrNull() ?: return null }
            return ReleaseVersion(major, minor, patch)
        }
    }
}
