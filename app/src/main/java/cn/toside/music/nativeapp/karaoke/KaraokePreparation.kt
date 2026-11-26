package cn.toside.music.nativeapp.karaoke

import android.content.Context
import android.net.Uri
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.AppPreferences
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.utils.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal typealias KaraokeInput = suspend (File, (Int) -> Unit) -> Uri

/** A page can stop observing while the application finishes and caches its accompaniment. */
internal class KaraokePreparation(private val context: Context) {
    enum class Status { WAITING, RUNNING, CANCELLING }
    data class Progress(val percent: Int = 0, val modelDownload: Int? = null, val modelInstalled: Boolean = false,
        val status: Status = Status.WAITING, val position: Int = 0)
    data class Prepared(val backing: KaraokeBacking, val modelEnabled: Boolean, val modelInstalled: Boolean, val message: Int?)
    data class QueueEntry(val id: String, val song: Song, val progress: Progress)
    class Cancelled : Exception()

    private data class Key(val source: String, val recording: String)

    private class Task(val directory: File, val key: Key, val song: Song,
        val input: KaraokeInput, val previous: KaraokeBacking?, val quality: String,
        val parameters: MdxParameters?, val installModel: Boolean) {
        val id get() = directory.name
        val progress = MutableStateFlow(Progress())
        val sourceStaged = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Result<Prepared>>()
        var readers = 0
        var finished = false
        var worker: Job? = null
        var cancelling = false
    }

    private val preferences = AppPreferences(context)
    private val cache = KaraokeBackingCache(context)
    private val mdx = MdxSeparation(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val root = context.cacheDir.resolve("karaoke-preparation")
    private val initialize by lazy { scope.async { root.deleteRecursively() } }
    private val tasks = linkedMapOf<Key, Task>()
    private var active: Task? = null
    private val queueState = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queue = queueState.asStateFlow()

    fun source(song: Song): KaraokeInput = source(song, cache.preferredQuality())

    private fun source(song: Song, quality: String): KaraokeInput = { target, progress ->
        KaraokeSource.input(context, song, quality, target, progress)
    }

    suspend fun prepare(
        song: Song,
        directory: File,
        input: KaraokeInput?,
        installModel: Boolean,
        previous: KaraokeBacking?,
        progress: (Progress) -> Unit,
    ): Prepared = withContext(Dispatchers.IO) {
        val quality = if (song.source == "local") "" else cache.preferredQuality()
        val parameters = MdxParameters.read(preferences).takeIf { preferences.bool(MdxParameters.ENABLED_KEY, true) }
        val key = Key(song.source, if (song.source == "local" && song.uri != Uri.EMPTY) song.uri.normalizeScheme().toString() else song.id.toString())
        if (!installModel && synchronized(tasks) { key !in tasks }) {
            val separation = parameters?.takeIf { mdx.hasModelInput() }
            val restored = cache.restoreCached(song, directory, separation, quality)
            if (restored != null) return@withContext Prepared(restored, parameters != null, separation != null, null)
        }
        val seed = previous?.takeIf { it.cacheKey == null || it.cacheKey == cache.sourceKey(song, quality) }
        val (task, stagingPrevious) = synchronized(tasks) {
            // Metadata and preparation settings can change while the same recording is in the queue.
            val running = tasks[key]
            val task = running ?: Task(root.resolve(UUID.randomUUID().toString()), key, song,
                input ?: source(song, quality), seed, quality, parameters, installModel && parameters != null).also { tasks[key] = it }
            task.readers++
            startNextLocked()
            publishLocked()
            task to (running == null && seed != null)
        }
        try {
            coroutineScope {
                val observer = launch { task.progress.collect { progress(it) } }
                try {
                    val prepared = task.result.await().getOrThrow()
                    val exclusive = synchronized(tasks) { task.readers == 1 }
                    check(directory.mkdirs() || directory.isDirectory)
                    fun transfer(file: File): File {
                        val target = directory.resolve(file.name)
                        if (!exclusive || !file.renameTo(target)) file.copyTo(target, overwrite = true)
                        return target
                    }
                    val backing = prepared.backing
                    val original = backing.original?.let(::transfer)
                    prepared.copy(backing = backing.copy(file = transfer(backing.file), original = original))
                } finally { observer.cancelAndJoin() }
            }
        } finally {
            // Keep the draft available until this worker has staged it or has been cancelled.
            if (stagingPrevious) withContext(NonCancellable) { task.sourceStaged.await() }
            val cleanup = synchronized(tasks) { --task.readers == 0 && task.finished }
            if (cleanup) task.directory.deleteRecursively()
        }
    }

    fun cancel(id: String) {
        val worker = synchronized(tasks) {
            val task = tasks.values.firstOrNull { it.id == id } ?: return
            if (task.cancelling) return
            if (task === active) {
                task.cancelling = true
                publishLocked()
                task.worker
            } else {
                finishLocked(task, Result.failure(Cancelled()))
                null
            }
        }
        worker?.cancel()
    }

    private fun startNextLocked() {
        if (active != null) return
        val task = tasks.values.firstOrNull() ?: return
        active = task
        var outcome: Result<Prepared>? = null
        val worker = scope.launch(start = CoroutineStart.LAZY) {
            outcome = runCatching {
                val seed = try {
                    initialize.await()
                    check(task.directory.mkdirs() || task.directory.isDirectory)
                    task.previous?.let { backing ->
                        fun stage(file: File) = file.copyTo(task.directory.resolve("previous-${file.name}"))
                        backing.copy(file = stage(backing.file), original = backing.original?.let(::stage))
                    }
                } finally { task.sourceStaged.complete(Unit) }
                convert(task, seed)
            }
        }
        task.worker = worker
        worker.invokeOnCompletion { cause ->
            synchronized(tasks) {
                val result = if (task.cancelling || cause is CancellationException) Result.failure(Cancelled())
                    else outcome ?: Result.failure(checkNotNull(cause))
                finishLocked(task, result)
            }
        }
        worker.start()
    }

    private fun finishLocked(task: Task, result: Result<Prepared>) {
        tasks.remove(task.key)
        task.finished = true
        if (active === task) active = null
        task.sourceStaged.complete(Unit)
        if (task.readers == 0) scope.launch { task.directory.deleteRecursively() }
        startNextLocked()
        publishLocked()
        task.result.complete(result)
    }

    private fun publishLocked() {
        queueState.value = tasks.values.mapIndexed { index, task ->
            val status = when {
                task.cancelling -> Status.CANCELLING
                task === active -> Status.RUNNING
                else -> Status.WAITING
            }
            val progress = task.progress.value.copy(status = status, position = index)
            task.progress.value = progress
            QueueEntry(task.id, task.song, progress)
        }
    }

    private fun updateProgress(task: Task, change: (Progress) -> Progress) = synchronized(tasks) {
        if (task !== active || task.cancelling) return@synchronized
        val updated = change(task.progress.value)
        if (updated != task.progress.value) {
            task.progress.value = updated
            publishLocked()
        }
    }

    private suspend fun convert(task: Task, previous: KaraokeBacking?): Prepared {
        val song = task.song
        val input = task.input
        val target = task.directory.resolve("backing.wav")
        val original = task.directory.resolve("original.wav")
        val downloaded = task.directory.resolve("source.audio")
        val parameters = task.parameters
        var message: Int? = null
        fun report(percent: Int) = updateProgress(task) { it.copy(percent = maxOf(it.percent, percent)) }
        fun logModelError(error: Exception) {
            DiagnosticLog(context).record("playback", "Karaoke MDX-Net: ${error.message}", "WARN")
        }
        val model = if (parameters == null) null else if (!task.installModel) mdx.installedModel() else try {
            if (mdx.installedModel() == null) {
                updateProgress(task) { it.copy(modelDownload = 0) }
                mdx.install { percent -> updateProgress(task) { it.copy(modelDownload = percent) } }
            }
            checkNotNull(mdx.installedModel())
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            logModelError(e)
            message = R.string.karaoke_model_download_error
            null
        } finally { updateProgress(task) { it.copy(modelDownload = null) } }
        if (model != null) updateProgress(task) { it.copy(modelInstalled = true) }

        suspend fun generate(useModel: File?, progressLimit: Int = 100): KaraokeBacking {
            fun reportStage(percent: Int) = report(percent * progressLimit / 100)
            val source = input(downloaded) { reportStage(it * DOWNLOAD_SHARE / 100) }
            val offset = if (source == Uri.fromFile(downloaded)) DOWNLOAD_SHARE else 0
            reportStage(offset)
            return KaraokeAudio.prepare(context, source, target, song.duration, original, useModel,
                parameters ?: MdxParameters.Default, onSeparationError = ::logModelError) {
                reportStage(offset + it * (100 - offset) / 100)
            }
        }

        suspend fun generateBasic(progressLimit: Int): KaraokeBacking {
            if (previous == null) return generate(null, progressLimit)
            check(previous.file.renameTo(target))
            val monitor = previous.original?.let { check(it.renameTo(original)); original }
            return previous.copy(file = target, original = monitor)
        }

        try {
            val backing = cache.reuseOrPrepare(song, target, original, parameters.takeIf { model != null }, task.quality) {
                if (!task.installModel) generate(model)
                else if (model == null) generateBasic(100)
                else {
                    val basic = cache.reuseOrPrepare(song, target, original, quality = task.quality) { generateBasic(20) }
                    try {
                        val monitor = basic.original ?: KaraokeAudio.prepareOriginal(context,
                            input(downloaded) { report(it / 5) }, original, basic.frames)
                        mdx.run(model, basic.copy(original = monitor), checkNotNull(parameters)) { report(20 + it * 80 / 100) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { logModelError(e); basic }
                }
            }
            if (model != null && !backing.separated) message = R.string.karaoke_model_fallback
            return Prepared(backing, parameters != null, model != null, message)
        } finally { downloaded.delete() }
    }

    private companion object {
        const val DOWNLOAD_SHARE = 45
    }
}
