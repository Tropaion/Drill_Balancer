package dev.drillbalancer.upload

import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.recorder.export.AudioMute
import dev.drillbalancer.recorder.export.Finalizer
import dev.drillbalancer.recorder.label.LabelEdits
import dev.drillbalancer.recorder.label.ReviewRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** One commit per recording through the Git Data API, on the in-memory GitHub of FakeGitHub. */
class DatasetUploaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val gh = FakeGitHub()
    private val labeler = UploadTestData.labeler()
    private val store by lazy { UploadTestData.store(tmp.root, labeler) }
    private val repo = "Tropaion/Drill_Balancer_Data"

    @After
    fun close() = gh.close()

    private fun uploader(api: GitHubApi = gh.api(), mute: Boolean = false, maxFileBytes: Long = DatasetUploader.MAX_FILE_BYTES, branch: String = "main", overwrite: Boolean = false) =
        DatasetUploader(api, store, repo, branch, mute, maxFileBytes, overwrite = overwrite)

    private fun dir(id: String) = store.dir(id)

    private fun localFiles(id: String): Map<String, String> {
        val meta = Finalizer.readJson(File(dir(id), Finalizer.META))!!
        val names = listOf(Finalizer.META, Finalizer.EVENTS) + (meta["files"] as Map<*, *>).keys.map { it.toString() }
        return names.associateWith { FakeGitHub.gitSha(File(dir(id), it).readBytes()) }
    }

    private fun remoteFolder(id: String): Map<String, String> {
        val p = UploadRecord.repoPath(id) + "/"
        return gh.files().filterKeys { it.startsWith(p) }.mapKeys { it.key.removePrefix(p) }
    }

    /**
     * The data repo setting changed after an upload: the upload to the new one moves upload.json
     * there on its first blob, but the repo set before is still known to hold a commit of it (what
     * Delete take says it keeps), also once the new one holds it, and back on the first one.
     */
    @Test
    fun aRepoSetBeforeIsKeptWhenTheUploadMovesToAnotherOne() {
        val id = UploadTestData.reviewed(store, labeler)
        uploader().upload(id)
        val other = FakeGitHub(owner = "someone", name = "data")
        try {
            var blobs = 0
            val cut = object : GitHubApi by other.api() {
                override fun createBlob(content: BlobContent, sent: (Long) -> Unit): String {
                    if (++blobs == 2) throw IOException("connection reset")
                    return other.api().createBlob(content, sent)
                }
            }
            val b = "someone/data"
            assertThrows(IOException::class.java) { DatasetUploader(cut, store, b).upload(id) }
            val rec = UploadRecord.read(dir(id))!!
            assertEquals("upload.json is the new repo's", b, rec.repo)
            assertNull(rec.commit)
            assertEquals(listOf(repo), rec.committedRepos)
            assertEquals(RepoCopy.ELSEWHERE, LocalUpload.of(dir(id), b).repoCopy)
            DatasetUploader(other.api(), store, b).upload(id)
            assertEquals(RepoCopy.CURRENT, LocalUpload.of(dir(id), b).repoCopy)
            assertEquals("the one before keeps it", setOf(b, repo), UploadRecord.read(dir(id))!!.repos)
            assertEquals("set back: it holds a commit", RepoCopy.OLDER, LocalUpload.of(dir(id), repo).repoCopy)
        } finally {
            other.close()
        }
    }

    @Test
    fun aNewRecordingIsOneCommit() {
        val id = UploadTestData.reviewed(store, labeler)
        val before = gh.history().size
        val progress = ArrayList<Pair<Long, Long>>()
        val r = uploader().upload(id) { s, t -> progress += s to t } as DatasetUploader.Result.Committed
        assertFalse(r.update)
        assertEquals(before + 1, gh.history().size)
        val c = gh.history().first()
        assertEquals(r.commit, gh.head())
        assertTrue(c.message, c.message.startsWith("Add $id: "))
        assertEquals("Add $id: ${DatasetUploader.summary(Finalizer.readJson(File(dir(id), Finalizer.META))!!)}", c.message)
        // exactly the dataset files, in recordings/YYYY/YYYY-MM/<id>/, byte for byte
        val local = localFiles(id)
        assertEquals(setOf("meta.json", "events.json", "imu.csv.gz", "level.csv.gz", "audio.flac", "audio_sync.csv", "device_events.csv"), local.keys)
        assertEquals(local, remoteFolder(id))
        assertTrue(UploadRecord.repoPath(id).matches(Regex("recordings/2026/2026-10/$id")))
        for ((n, sha) in local) assertTrue(n, gh.blob(sha)!!.contentEquals(File(dir(id), n).readBytes()))
        // the files before stay, phone-only files are not sent
        assertTrue("README.md" in gh.files())
        assertFalse(gh.files().keys.any { it.endsWith("take.json") || it.endsWith("stream.dbr") || it.endsWith("lanes.bin") || it.endsWith(UploadRecord.FILE) })
        assertEquals(local.size, gh.blobPosts.size)
        assertEquals(local.keys.sorted(), r.uploaded.sorted())
        // upload.json: the blobs, the commit, the files as committed
        val rec = UploadRecord.read(dir(id))!!
        assertEquals(r.commit, rec.commit)
        assertEquals(local, rec.committed)
        assertEquals(repo, rec.repo)
        assertEquals("main", rec.branch)
        assertNull(rec.error)
        assertTrue(LocalUpload.of(dir(id), repo).done)
        assertFalse("another repository: not done there", LocalUpload.of(dir(id), "Tropaion/Other").done)
        // progress over the blob bytes
        val total = dir(id).let { d -> local.keys.sumOf { File(d, it).length() } }
        assertEquals(total to total, progress.last())
    }

    @Test
    fun aLabelChangeUploadsOnlyTheChangedFiles() {
        val id = UploadTestData.reviewed(store, labeler)
        val first = (uploader().upload(id) as DatasetUploader.Result.Committed).commit
        val posted = gh.blobPosts.size
        assertTrue(LocalUpload.of(dir(id), repo).done)
        UploadTestData.save(store, labeler, id) { l -> LabelEdits.add(l, "kickback", l.c0 + 300_000).first }
        assertFalse("meta.json changed: not done", LocalUpload.of(dir(id), repo).done)
        val r = uploader().upload(id) as DatasetUploader.Result.Committed
        assertTrue(r.update)
        assertEquals("Update labels of $id", r.message)
        assertEquals(listOf("events.json", "meta.json"), r.uploaded.sorted())
        assertEquals(posted + 2, gh.blobPosts.size)
        assertEquals(listOf(first), gh.commitOf(r.commit).parents)
        assertEquals(localFiles(id), remoteFolder(id))
    }

    @Test
    fun aRecordingAlreadyThereIsNotCommittedAgain() {
        val id = UploadTestData.reviewed(store, labeler)
        val c = (uploader().upload(id) as DatasetUploader.Result.Committed).commit
        val commits = gh.history().size
        val posts = gh.blobPosts.size
        // the same files: no commit, nothing sent
        assertEquals(DatasetUploader.Result.AlreadyThere(c), uploader().upload(id))
        // upload.json lost (the phone's data restored): HEAD's meta.json says it is there
        File(dir(id), UploadRecord.FILE).delete()
        assertEquals(DatasetUploader.Result.AlreadyThere(c), uploader().upload(id))
        assertEquals(commits, gh.history().size)
        assertEquals(posts, gh.blobPosts.size)
        assertEquals(c, UploadRecord.read(dir(id))!!.commit)
        assertTrue(LocalUpload.of(dir(id), repo).done)
    }

    @Test
    fun anUploadCutShortResumesFromUploadJson() {
        val id = UploadTestData.reviewed(store, labeler)
        var posts = 0
        gh.intercept = { r ->
            if (r.method == "POST" && r.url.encodedPath.endsWith("/git/blobs") && ++posts == 4) {
                MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()        // the network goes
            } else {
                null
            }
        }
        try {
            uploader().upload(id)
            fail("no IOException")
        } catch (_: IOException) {
        }
        val kept = UploadRecord.read(dir(id))!!.blobs
        assertEquals(3, kept.size)
        assertNull(UploadRecord.read(dir(id))!!.commit)
        gh.intercept = null
        val sent = gh.blobPosts.toList()
        val r = uploader().upload(id) as DatasetUploader.Result.Committed
        assertTrue("the 3 blobs are not sent again", r.uploaded.none { it in kept.keys })
        assertEquals(localFiles(id).size - 3, gh.blobPosts.size - sent.size)
        assertEquals(localFiles(id), remoteFolder(id))
    }

    @Test
    fun blobsGoneAfterAGcAreSentAgainOnce() {
        val id = UploadTestData.reviewed(store, labeler)
        var trees = 0
        gh.intercept = { r ->
            if (r.method == "POST" && r.url.encodedPath.endsWith("/git/trees") && ++trees == 1) {
                MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
            } else {
                null
            }
        }
        try {
            uploader().upload(id)
            fail("no IOException")
        } catch (_: IOException) {
        }
        assertEquals(localFiles(id).size, UploadRecord.read(dir(id))!!.blobs.size)
        gh.intercept = null
        gh.gc()                                       // GitHub dropped the blobs no commit holds
        val posts = gh.blobPosts.size
        val r = uploader().upload(id) as DatasetUploader.Result.Committed
        assertEquals("every blob again", localFiles(id).size, gh.blobPosts.size - posts)
        assertEquals(localFiles(id).keys.sorted(), r.uploaded.sorted())
        assertEquals(localFiles(id), remoteFolder(id))
    }

    @Test
    fun aTreeRefusedWithoutRememberedBlobsIsAnError() {
        val id = UploadTestData.reviewed(store, labeler)
        gh.intercept = { r -> if (r.url.encodedPath.endsWith("/git/trees")) FakeGitHub.error(422, "Invalid tree info") else null }
        try {
            uploader().upload(id)
            fail("no 422")
        } catch (e: GitHubException.Unprocessable) {
            assertEquals("Invalid tree info", e.message)
        }
    }

    @Test
    fun aBranchThatMovedIsTriedAgainOnItsNewCommit() {
        val id = UploadTestData.reviewed(store, labeler)
        var patches = 0
        gh.beforePatch = { b -> if (++patches <= 2) gh.pushOther(b) }        // two pushes race ours
        val r = uploader().upload(id) as DatasetUploader.Result.Committed
        assertEquals(3, patches)
        assertEquals(localFiles(id).size, gh.blobPosts.size)                 // blobs reused
        val h = gh.history()
        assertEquals(r.commit, gh.head())
        assertEquals("Update index", h[1].message)                          // on top of the other pushes
        assertEquals("Update index", h[2].message)
        assertTrue("the other push's file stays", h[0].tree["index.csv"] == h[1].tree["index.csv"])
        assertEquals(localFiles(id), remoteFolder(id))
    }

    @Test
    fun aBranchThatKeepsMovingGivesUpAfterFiveTries() {
        val id = UploadTestData.reviewed(store, labeler)
        var patches = 0
        gh.beforePatch = { b ->
            patches++
            gh.pushOther(b)
        }
        try {
            uploader().upload(id)
            fail("no give-up")
        } catch (e: UploadException) {
            assertEquals(UploadException.Kind.BRANCH_MOVING, e.kind)
            assertTrue(e.retryable)
        }
        assertEquals(5, patches)
        assertNull(UploadRecord.read(dir(id))!!.commit)
    }

    @Test
    fun anEmptyRepositoryNeedsTheTemplate() {
        FakeGitHub(empty = true).use { empty ->
            val id = UploadTestData.reviewed(store, labeler)
            try {
                uploader(empty.api()).upload(id)
                fail("no error")
            } catch (e: UploadException) {
                assertEquals(UploadException.Kind.REPO_EMPTY, e.kind)
                assertFalse(e.retryable)
            }
            assertTrue(empty.blobPosts.isEmpty())
        }
    }

    @Test
    fun aMissingBranchOrRepository() {
        val id = UploadTestData.reviewed(store, labeler)
        try {
            uploader(branch = "nope").upload(id)
            fail()
        } catch (e: UploadException) {
            assertEquals(UploadException.Kind.BRANCH_MISSING, e.kind)
        }
        FakeGitHub(name = "Other").use { other ->
            try {
                DatasetUploader(HttpGitHubApi("Tropaion", "Drill_Balancer_Data", other.token, other.baseUrl), store, repo).upload(id)
                fail()
            } catch (e: GitHubException.NotFound) {
            }
        }
    }

    @Test
    fun githubsRefusalsComeThrough() {
        val id = UploadTestData.reviewed(store, labeler)
        try {
            uploader(gh.api(token = "fake_token_wrong_00000000000000000")).upload(id)
            fail()
        } catch (_: GitHubException.Unauthorized) {
        }
        gh.intercept = { r -> if (r.url.encodedPath.endsWith("/git/blobs")) FakeGitHub.error(403, "Resource not accessible by personal access token") else null }
        try {
            uploader().upload(id)
            fail()
        } catch (_: GitHubException.Forbidden) {
        }
        gh.intercept = { r -> if (r.url.encodedPath.endsWith("/git/blobs")) FakeGitHub.error(429, "slow down", "Retry-After" to "7") else null }
        try {
            uploader().upload(id)
            fail()
        } catch (e: GitHubException.RateLimited) {
            assertEquals(7_000L, e.waitMs)
        }
        gh.intercept = { r -> if (r.url.encodedPath.endsWith("/git/blobs")) FakeGitHub.error(403, "secondary", "Retry-After" to "2") else null }
        try {
            uploader().upload(id)
            fail()
        } catch (e: GitHubException.RateLimited) {
            assertEquals(2_000L, e.waitMs)
            assertEquals(403, e.status)
        }
    }

    @Test
    fun aFileOverTheLimitIsRefusedBeforeAnythingIsSent() {
        val id = UploadTestData.reviewed(store, labeler)
        // a sparse 101 MB imu.csv.gz: refused on its size, never read
        RandomAccessFile(File(dir(id), Finalizer.IMU), "rw").use { it.setLength(101L * 1024 * 1024) }
        try {
            uploader().upload(id)
            fail("no refusal")
        } catch (e: UploadException) {
            assertEquals(UploadException.Kind.TOO_LARGE, e.kind)
            assertTrue(e.message!!, e.message!!.startsWith("imu.csv.gz is 105.9 MB"))
        }
        assertTrue("nothing asked of GitHub", gh.requests.isEmpty())
        try {
            uploader(maxFileBytes = 1000).upload(UploadTestData.reviewed(store, labeler, "golden"))
            fail()
        } catch (e: UploadException) {
            assertEquals(UploadException.Kind.TOO_LARGE, e.kind)
        }
        assertTrue(gh.requests.isEmpty())
        assertEquals(95L * 1024 * 1024, DatasetUploader.MAX_FILE_BYTES)
    }

    @Test
    fun filesThatDoNotMatchMetaJsonAreNotSent() {
        val id = UploadTestData.reviewed(store, labeler)
        val f = File(dir(id), Finalizer.SYNC)
        f.appendText("1,2\n")
        try {
            uploader().upload(id)
            fail()
        } catch (e: UploadException) {
            assertEquals(UploadException.Kind.FILES_CHANGED, e.kind)
            assertTrue(e.message!!, "audio_sync.csv" in e.message!!)
        }
        assertTrue(gh.requests.isEmpty())
    }

    @Test
    fun aStorageErrorIsThePhonesNotTheNetworks() {
        val id = UploadTestData.reviewed(store, labeler)
        val f = File(dir(id), Finalizer.AUDIO)
        check(f.setReadable(false))
        try {
            uploader().upload(id)
            fail()
        } catch (e: UploadException) {
            assertEquals(UploadException.Kind.STORAGE, e.kind)
            assertTrue(e.retryable)
            assertTrue(UploadMessages.of(e, repo, "main"), UploadMessages.of(e, repo, "main").startsWith("Not uploaded: the recording's files could not be read"))
        } finally {
            f.setReadable(true)
        }
        assertTrue(gh.requests.isEmpty())
    }

    @Test
    fun anUnreviewedRecordingIsNotUploaded() {
        val j = dev.drillbalancer.recorder.export.FinalizerFixtures.install(store, "golden")
        store.finalize(j.recordingId)
        assertEquals(RecordingStore.AUTO_ONLY, store.list().single().reviewStatus)
        assertEquals(DatasetUploader.Result.NotReviewed, uploader().upload(j.recordingId))
        assertTrue(gh.requests.isEmpty())
    }

    /**
     * A Save while the blobs go: the commit holds the files as they were when the upload began
     * (it reads the files it opened), the recording is then not done, and the next upload
     * commits the new labels.
     */
    @Test
    fun aSaveDuringAnUploadIsUploadedNext() {
        val id = UploadTestData.reviewed(store, labeler)
        val before = localFiles(id)
        var posts = 0
        gh.intercept = { r ->
            if (r.url.encodedPath.endsWith("/git/blobs") && ++posts == 2) {
                UploadTestData.save(store, labeler, id) { l -> LabelEdits.add(l, "kickback", l.c0 + 300_000).first }
            }
            null
        }
        uploader().upload(id)
        gh.intercept = null
        assertEquals("the snapshot was committed", before, remoteFolder(id))
        val after = localFiles(id)
        assertNotEquals(before[Finalizer.META], after[Finalizer.META])
        assertFalse(LocalUpload.of(dir(id), repo).done)
        val r = uploader().upload(id) as DatasetUploader.Result.Committed
        assertTrue(r.update)
        assertEquals(after, remoteFolder(id))
        assertTrue(LocalUpload.of(dir(id), repo).done)
    }

    @Test
    fun withMuteTheMutedAudioIsUploadedAndKeptOnThePhone() {
        val id = UploadTestData.reviewed(store, labeler) { l -> UploadTestData.motorRun(l, 0.5, 1.0) }
        uploader(mute = true).upload(id)
        val meta = Finalizer.readJson(File(dir(id), Finalizer.META))!!
        assertTrue(AudioMute.mutedSpans(meta).isNotEmpty())
        assertEquals(localFiles(id), remoteFolder(id))
        // again: nothing to mute, nothing to send
        val posts = gh.blobPosts.size
        assertTrue(uploader(mute = true).upload(id) is DatasetUploader.Result.AlreadyThere)
        assertEquals(posts, gh.blobPosts.size)
    }

    @Test
    fun theTokenIsInNoFileAndNoMessage() {
        val id = UploadTestData.reviewed(store, labeler)
        val logged = ArrayList<String>()
        gh.intercept = { r -> if (r.url.encodedPath.endsWith("/git/trees")) FakeGitHub.error(422, "bad ${FakeGitHub.TOKEN}") else null }
        try {
            uploader(gh.api(log = { logged += it })).upload(id)
            fail()
        } catch (e: GitHubException) {
            assertFalse(FakeGitHub.TOKEN in e.toString())
            assertFalse(FakeGitHub.TOKEN in UploadMessages.of(e, repo, "main"))
        }
        gh.intercept = null
        uploader(gh.api(log = { logged += it })).upload(id)
        assertTrue(logged.isNotEmpty())
        assertFalse(logged.any { FakeGitHub.TOKEN in it })
        assertEquals(emptyList<File>(), UploadTestData.filesHolding(tmp.root, FakeGitHub.TOKEN))
    }

    @Test
    fun theCommitSummary() {
        val id = UploadTestData.reviewed(store, labeler, "golden")
        val meta = Finalizer.readJson(File(dir(id), Finalizer.META))!!
        val s = DatasetUploader.summary(meta)
        assertTrue(s, Regex("^[a-z_/]+( [0-9.]+ mm)?, [a-z]+, [0-9]+ s$").matches(s))
    }

    private fun relabel(id: String) = UploadTestData.save(store, labeler, id) { l -> LabelEdits.add(l, "kickback", l.c0 + 300_000).first }

    /**
     * The data repo's meta.json changed since this phone uploaded it (dbdata.autodetect --write
     * --force on the PC): a phone Save does not silently put its labels over it (CONFLICT, with
     * the reason); the user's Retry (overwrite) does.
     */
    @Test
    fun aLabelChangeInTheDataRepoIsNotOverwritten() {
        val id = UploadTestData.reviewed(store, labeler)
        uploader().upload(id)
        val pc = "{\"relabelled\": \"on the PC\"}\n".toByteArray()
        gh.pushFile("${UploadRecord.repoPath(id)}/${Finalizer.META}", pc)
        val head = gh.head()
        relabel(id)
        try {
            uploader().upload(id)
            fail("no conflict")
        } catch (e: UploadException) {
            assertEquals(UploadException.Kind.CONFLICT, e.kind)
            assertFalse(e.retryable)
            assertTrue(UploadMessages.of(e, repo, "main"), UploadMessages.of(e, repo, "main").contains("Retry upload overwrites the data repo's labels with the phone's"))
            assertEquals(FailureKind.CONFLICT, UploadMessages.kind(e))
        }
        assertEquals("nothing committed over it", head, gh.head())
        assertEquals(FakeGitHub.gitSha(pc), remoteFolder(id)[Finalizer.META])
        val r = uploader(overwrite = true).upload(id) as DatasetUploader.Result.Committed
        assertEquals("Update labels of $id", r.message)
        assertEquals(localFiles(id), remoteFolder(id))
        // the phone's own earlier commits are no conflict: another Save goes as before
        relabel(id)
        assertTrue(uploader().upload(id) is DatasetUploader.Result.Committed)
    }

    /**
     * An upload whose commit lost the race (422) to a newer upload of the same recording (a
     * stopped worker still running): it does not commit its older files over the newer ones.
     */
    @Test
    fun anOlderUploadDoesNotRevertANewerOne() {
        val id = UploadTestData.reviewed(store, labeler)
        uploader().upload(id)
        relabel(id)                                     // the old one's snapshot: this Save
        val newer = "{\"newer\": true}\n".toByteArray()
        var patches = 0
        gh.beforePatch = { b ->
            // a newer upload of the recording commits first
            if (++patches == 1) gh.pushFile("${UploadRecord.repoPath(id)}/${Finalizer.META}", newer, b, "Update labels of $id")
        }
        try {
            uploader().upload(id)
            fail("committed over the newer upload")
        } catch (e: UploadException) {
            assertEquals(UploadException.Kind.CONFLICT, e.kind)
        }
        assertEquals(1, patches)
        assertEquals(FakeGitHub.gitSha(newer), remoteFolder(id)[Finalizer.META])
    }

    /** upload.json that cannot be written is the phone's storage, retryable, not "No connection to GitHub". */
    @Test
    fun anUploadJsonWriteErrorIsStorage() {
        val id = UploadTestData.reviewed(store, labeler)
        check(File(dir(id), "${UploadRecord.FILE}.tmp").mkdir())            // writeAtomic's temporary file cannot be made
        try {
            uploader().upload(id)
            fail()
        } catch (e: UploadException) {
            assertEquals(UploadException.Kind.STORAGE, e.kind)
            assertTrue(e.retryable)
            assertTrue(UploadMessages.of(e, repo, "main").startsWith("Not uploaded: the recording's files could not be read or written"))
        } finally {
            File(dir(id), "${UploadRecord.FILE}.tmp").delete()
        }
        assertTrue(uploader().upload(id) is DatasetUploader.Result.Committed)
    }

    /** More blob bytes to send than [DatasetUploader]'s sendLimit: nothing is sent (NEEDS_FOREGROUND). */
    @Test
    fun overTheSendLimitNothingIsSent() {
        val id = UploadTestData.reviewed(store, labeler)
        try {
            DatasetUploader(gh.api(), store, repo, sendLimit = 1000).upload(id)
            fail()
        } catch (e: UploadException) {
            assertEquals(UploadException.Kind.NEEDS_FOREGROUND, e.kind)
        }
        assertTrue(gh.blobPosts.isEmpty())
        // a label change after an upload is small: it goes
        uploader().upload(id)
        relabel(id)
        assertTrue(DatasetUploader(gh.api(), store, repo, sendLimit = 100_000).upload(id) is DatasetUploader.Result.Committed)
    }

    /**
     * A mute the app died in between its renames (muted audio.flac, the meta.json before,
     * meta.json.tmp for the muted audio), then mute switched off: the upload finishes the pair
     * and goes; it did fail for good (FILES_CHANGED). A Save in between keeps the spans.
     */
    @Test
    fun aMuteStoppedHalfWayIsFinishedEvenWithMuteOff() {
        val id = UploadTestData.reviewed(store, labeler) { l -> UploadTestData.motorRun(l, 0.5, 1.0) }
        val d = dir(id)
        val before = File(d, Finalizer.META).readBytes()
        assertTrue(AudioMute.apply(d))
        File(d, Finalizer.META).copyTo(File(d, "${Finalizer.META}.tmp"))
        File(d, Finalizer.META).writeBytes(before)                       // the second rename never happened
        val r = uploader(mute = false).upload(id) as DatasetUploader.Result.Committed
        assertFalse(r.update)
        val meta = Finalizer.readJson(File(d, Finalizer.META))!!
        assertTrue(AudioMute.mutedSpans(meta).isNotEmpty())
        assertEquals(localFiles(id), remoteFolder(id))
        assertFalse(File(d, "${Finalizer.META}.tmp").exists())

    }

    /** The same half-done mute, then a Save before the next upload: the Save finishes the pair first, so the muted spans stay declared. */
    @Test
    fun aSaveAfterAMuteStoppedHalfWayKeepsItsSpans() {
        val id = UploadTestData.reviewed(store, labeler) { l -> UploadTestData.motorRun(l, 0.5, 1.0) }
        val d = dir(id)
        val before = File(d, Finalizer.META).readBytes()
        assertTrue(AudioMute.apply(d))
        val spans = AudioMute.mutedSpans(Finalizer.readJson(File(d, Finalizer.META))).map { it.toList() }
        File(d, Finalizer.META).copyTo(File(d, "${Finalizer.META}.tmp"))
        File(d, Finalizer.META).writeBytes(before)
        relabel(id)
        assertEquals(spans, AudioMute.mutedSpans(Finalizer.readJson(File(d, Finalizer.META))).map { it.toList() })
        assertTrue(uploader(mute = false).upload(id) is DatasetUploader.Result.Committed)
        assertEquals(localFiles(id), remoteFolder(id))
        // finished: nothing more to finish
        assertFalse(AudioMute.finish(d))
    }

    /**
     * The half-done mute after the review was opened (its load finished nothing then), then that
     * review's Save: the Save finishes the pair before it writes its own meta.json.tmp over the
     * mute's, so the spans stay declared and meta.files matches the muted audio.
     */
    @Test
    fun aSaveOfAReviewOpenedBeforeAMuteStoppedHalfWayKeepsItsSpans() {
        val id = UploadTestData.reviewed(store, labeler) { l -> UploadTestData.motorRun(l, 0.5, 1.0) }
        val d = dir(id)
        val review = ReviewRepository(store, labeler)
        val data = review.load(id)                                       // the review screen is open
        val before = File(d, Finalizer.META).readBytes()
        assertTrue(AudioMute.apply(d))
        val spans = AudioMute.mutedSpans(Finalizer.readJson(File(d, Finalizer.META))).map { it.toList() }
        File(d, Finalizer.META).copyTo(File(d, "${Finalizer.META}.tmp"))
        File(d, Finalizer.META).writeBytes(before)                       // the mute's second rename failed
        review.save(data, LabelEdits.add(LabelEdits.confirmAll(data.labels), "kickback", data.labels.c0 + 300_000).first, 1)
        assertEquals(spans, AudioMute.mutedSpans(Finalizer.readJson(File(d, Finalizer.META))).map { it.toList() })
        assertTrue(uploader(mute = false).upload(id) is DatasetUploader.Result.Committed)
        assertEquals(localFiles(id), remoteFolder(id))
    }

    /**
     * An update commit removes only the files this phone committed there that the recording no
     * longer has: a file a PC tool added to the recording's folder stays.
     */
    @Test
    fun anUpdateRemovesOnlyFilesThePhoneCommitted() {
        val id = UploadTestData.reviewed(store, labeler)
        uploader().upload(id)
        val folder = UploadRecord.repoPath(id)
        val notes = "checked on the PC\n".toByteArray()
        gh.pushFile("$folder/notes.md", notes)                                   // never the phone's
        // a file the phone committed there before that the recording no longer has
        val old = "t_us\n".toByteArray()
        gh.pushFile("$folder/old.csv", old, message = "Update labels of $id")
        val rec = UploadRecord.read(dir(id))!!
        UploadRecord.write(dir(id), rec.copy(committed = rec.committed + ("old.csv" to FakeGitHub.gitSha(old))))
        relabel(id)
        assertTrue((uploader().upload(id) as DatasetUploader.Result.Committed).update)
        assertEquals(localFiles(id) + ("notes.md" to FakeGitHub.gitSha(notes)), remoteFolder(id))
    }

    /** A 422 on the PATCH while the branch did not move is GitHub refusing it (a protected branch): no redo, its reason in the message. */
    @Test
    fun aBranchGitHubWillNotMoveIsRefusedAtOnce() {
        val id = UploadTestData.reviewed(store, labeler)
        var patches = 0
        gh.intercept = { r ->
            if (r.method == "PATCH") {
                patches++
                FakeGitHub.error(422, "Protected branch update failed for refs/heads/main.")
            } else {
                null
            }
        }
        try {
            uploader().upload(id)
            fail("committed")
        } catch (e: UploadException) {
            assertEquals(UploadException.Kind.BRANCH_REFUSED, e.kind)
            assertFalse(e.retryable)
            val m = UploadMessages.of(e, repo, "main")
            assertTrue(m, m.startsWith("Not uploaded: GitHub refused to move the branch main (Protected branch update failed"))
            assertEquals(FailureKind.REFUSED, UploadMessages.kind(e))
        }
        assertEquals(1, patches)
        assertEquals(1, gh.requests.count { it.startsWith("POST") && it.endsWith("/git/commits") })
    }

    /** Mute on and audio that cannot be muted as it is: not retried (no STORAGE), the cause in the message, nothing sent. */
    @Test
    fun audioThatCannotBeMutedSaysWhyAndIsNotRetried() {
        val id = UploadTestData.reviewed(store, labeler) { l -> UploadTestData.motorRun(l, 0.5, 1.0) }
        val metaFile = File(dir(id), Finalizer.META)
        val bytes = metaFile.readBytes()
        val meta = Finalizer.readJson(metaFile)!!
        val sync = meta["sync"] as JsonObject
        metaFile.writeText(JsonObject(meta + ("sync" to JsonObject(sync + ("b_us_per_frame" to JsonPrimitive(0))))).toString())
        fun refused(cause: String) = try {
            uploader(mute = true).upload(id)
            fail("uploaded")
        } catch (e: UploadException) {
            assertEquals(UploadException.Kind.CANNOT_MUTE, e.kind)
            assertFalse(e.retryable)
            val m = UploadMessages.of(e, repo, "main")
            assertTrue(m, m.startsWith("Not uploaded: the audio cannot be muted (") && cause in m)
            assertEquals(FailureKind.RECORDING, UploadMessages.kind(e))
        }
        refused("meta.sync has no usable fit")
        metaFile.writeBytes(bytes)
        check(File(dir(id), Finalizer.AUDIO).delete())
        refused("audio.flac is missing")
        assertTrue(gh.requests.isEmpty())
    }

    /** upload.json from before error kinds (none) still loads; a kind this version does not know is OTHER. */
    @Test
    fun anUploadJsonWithoutAnErrorKindStillLoads() {
        val d = tmp.newFolder("r")
        fun read(extra: String) = File(d, UploadRecord.FILE).let {
            it.writeText("{\"schema\": \"drillbalancer.upload/1\", \"repo\": \"o/n\", \"branch\": \"main\", \"path\": \"p\", \"error\": \"x\"$extra}")
            UploadRecord.read(d)!!
        }
        assertEquals("x", read("").error)
        assertNull(read("").failure)
        assertEquals(FailureKind.CONFLICT, read(", \"error_kind\": \"conflict\"").failure)
        assertEquals(FailureKind.OTHER, read(", \"error_kind\": \"from_a_newer_app\"").failure)
    }
}
