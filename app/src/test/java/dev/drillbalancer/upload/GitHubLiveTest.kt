package dev.drillbalancer.upload

import dev.drillbalancer.recorder.export.Finalizer
import dev.drillbalancer.recorder.label.LabelEdits
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Opt-in, against the real data repository (app/README.md, Upload): skipped unless
 * DB_GITHUB_TOKEN and DB_GITHUB_LIVE_BRANCH are set. It refuses a branch named main (or the
 * repository's default branch) or one that exists already; otherwise it makes that branch from
 * main in Tropaion/Drill_Balancer_Data, uploads the finalizer's small golden take to it (one
 * commit), uploads a label change (a second commit), uploads again with and without upload.json
 * (no third commit), reads the folder back and compares the blob shas with the phone's files,
 * and deletes the branch it made (only that one). It prints the commit shas and the file list,
 * nothing else; the token is in no message.
 *
 *   DB_GITHUB_TOKEN=... DB_GITHUB_LIVE_BRANCH=upload-live-test \
 *     ./gradlew testDebugUnitTest --tests '*GitHubLiveTest' --rerun
 */
class GitHubLiveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun uploadToALiveBranch() {
        val token = System.getenv("DB_GITHUB_TOKEN").orEmpty()
        val branch = System.getenv("DB_GITHUB_LIVE_BRANCH").orEmpty().trim()
        assumeTrue("DB_GITHUB_TOKEN and DB_GITHUB_LIVE_BRANCH not set: the live upload test is opt-in", token.isNotBlank() && branch.isNotBlank())
        check(BRANCH.matches(branch) && branch !in setOf("main", "master")) { "refusing the branch \"$branch\": use a new test branch, never main" }

        val owner = "Tropaion"
        val name = "Drill_Balancer_Data"
        val repo = "$owner/$name"
        val api = HttpGitHubApi(owner, name, token)
        val info = api.repo()
        check(branch != info.defaultBranch) { "refusing the default branch \"$branch\"" }
        check(!api.branchExists(branch)) { "refusing: the branch \"$branch\" exists already (this test only deletes a branch it made)" }

        val labeler = UploadTestData.labeler()
        val store = UploadTestData.store(tmp.root, labeler)
        api.createBranch(branch, api.headCommit("main"))
        try {
            val id = UploadTestData.reviewed(store, labeler, "golden")
            val path = UploadRecord.repoPath(id)
            val up = DatasetUploader(api, store, repo, branch)

            val first = up.upload(id) as DatasetUploader.Result.Committed
            println("live: commit 1 ${first.commit} (${first.message})")
            assertFalse(first.update)

            UploadTestData.save(store, labeler, id) { l -> LabelEdits.add(l, "kickback", l.c0 + 20_000).first }
            val second = up.upload(id) as DatasetUploader.Result.Committed
            println("live: commit 2 ${second.commit} (${second.message})")
            assertTrue(second.update)
            assertEquals(listOf("events.json", "meta.json"), second.uploaded.sorted())

            assertEquals(DatasetUploader.Result.AlreadyThere(second.commit), up.upload(id))
            File(store.dir(id), UploadRecord.FILE).delete()
            assertEquals(DatasetUploader.Result.AlreadyThere(second.commit), up.upload(id))
            assertEquals("no third commit", second.commit, api.headCommit(branch))

            val remote = api.listFolder(path, second.commit)!!
            val meta = Finalizer.readJson(File(store.dir(id), Finalizer.META))!!
            val listed = (meta["files"] as JsonObject).keys
            val names = listOf(Finalizer.META, Finalizer.EVENTS) + Finalizer.DATASET_FILES.filter { it in listed }
            val local = names.associateWith { GitHash.blobSha(File(store.dir(id), it)) }
            assertEquals(local, remote)
            for ((n, sha) in remote.toSortedMap()) println("live: $path/$n $sha")
        } finally {
            api.deleteBranch(branch)
            assertFalse("the test branch is gone", api.branchExists(branch))
            println("live: deleted the branch $branch")
        }
    }

    private companion object {
        val BRANCH = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]{0,99}$")
    }
}
