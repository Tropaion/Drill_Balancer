package dev.drillbalancer.update

/** Updating the app itself. Debug builds have none: their signature cannot update a release install. */
class AppUpdates(val version: ReleaseVersion?, val installer: ApkInstaller)

/** The update notices' parts, built once in DrillBalancerApp. */
class UpdateDeps(
    val checker: UpdateChecker,
    val store: UpdateStore,
    val downloader: ReleaseDownloader,
    val app: AppUpdates? = null,
)
