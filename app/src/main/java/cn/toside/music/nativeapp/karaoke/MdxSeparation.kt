package cn.toside.music.nativeapp.karaoke

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.system.Os
import androidx.annotation.Keep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.function.IntPredicate

internal const val KARAOKE_MODEL_ASSETS = "karaoke-models"

internal class MdxSeparation(private val context: Context) {
    private val model = context.noBackupFilesDir.resolve("karaoke-models/$MODEL_FILE")

    internal fun hasModelInput(): Boolean = model.length() == MODEL_BYTES ||
        runCatching { MODEL_FILE in context.assets.list(KARAOKE_MODEL_ASSETS).orEmpty() }.getOrDefault(false)

    suspend fun installedModel(): File? = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                model.takeIf { hasVerifiedModel() || installBundledModel() }
            } catch (_: IOException) {
                null
            }
        }
    }

    suspend fun install(progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (hasVerifiedModel() || installBundledModel()) return@withLock
            stageModel { staged ->
                KaraokeSource.copy(MODEL_URL, emptyMap(), staged, limitBytes = MODEL_BYTES, progress = progress)
            }
        }
    }

    private suspend fun hasVerifiedModel(): Boolean {
        if (model.length() != MODEL_BYTES || (verifiedModel != model && !matchesDigest(model))) return false
        verifiedModel = model
        return true
    }

    private suspend fun installBundledModel(): Boolean {
        val bundled = try {
            context.assets.open("$KARAOKE_MODEL_ASSETS/$MODEL_FILE")
        } catch (_: FileNotFoundException) {
            return false
        }
        bundled.use { input ->
            stageModel { staged -> staged.outputStream().use { input.copyTo(it, 64 * 1024) } }
        }
        return true
    }

    private suspend fun stageModel(write: suspend (File) -> Unit) {
        val folder = checkNotNull(model.parentFile)
        if (!folder.mkdirs() && !folder.isDirectory) throw IOException("Cannot create the model directory")
        val staged = File.createTempFile("mdx-", ".onnx", folder)
        try {
            write(staged)
            if (!matchesDigest(staged)) throw IOException("MDX-Net model checksum mismatch")
            currentCoroutineContext().ensureActive()
            if (!staged.renameTo(model)) throw IOException("Cannot install the MDX-Net model")
            verifiedModel = model
        } finally {
            staged.delete()
        }
    }

    private suspend fun matchesDigest(file: File): Boolean {
        if (file.length() != MODEL_BYTES) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == MODEL_HASH
    }

    suspend fun run(model: File, backing: KaraokeBacking, parameters: MdxParameters, progress: (Int) -> Unit): KaraokeBacking =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val manager = context.getSystemService(ActivityManager::class.java)
                val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
                val required = if (Process.is64Bit()) MIN_AVAILABLE_64_BIT else MIN_AVAILABLE_32_BIT
                check(!memory.lowMemory && memory.availMem >= required) { "Not enough memory for MDX-Net" }
                try {
                    var lastMemoryCheck = 0
                    separate(model, checkNotNull(backing.original), backing.file, backing.frames, parameters) { percent ->
                        if (percent > lastMemoryCheck && percent < 100) {
                            manager.getMemoryInfo(memory)
                            check(!memory.lowMemory) { "Low memory during MDX-Net inference" }
                            lastMemoryCheck = percent
                        }
                        progress(percent)
                    }
                } catch (e: LinkageError) {
                    throw IllegalStateException("Cannot load ONNX Runtime", e)
                }
                backing.copy(separation = parameters)
            }
        }

    private companion object {
        val lock = Mutex()
        var verifiedModel: File? = null
        const val MODEL_FILE = "UVR_MDXNET_1_9703.onnx"
        const val MODEL_BYTES = 29_704_436L
        // Allow headroom over measured inference PSS: about 560 MiB on ARMv7 and 670 MiB on ARM64.
        const val MIN_AVAILABLE_32_BIT = 640L * 1024 * 1024
        const val MIN_AVAILABLE_64_BIT = 1024L * 1024 * 1024
        const val MODEL_HASH = "229ad3bb96a037e89d8ed86732d6d3675856e6a07c3e3f02896eac01ec7ee4be"
        const val MODEL_URL = "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/$MODEL_FILE"
    }
}

internal suspend fun separate(
    model: File, source: File, target: File, frames: Long, parameters: MdxParameters = MdxParameters.Default,
    progress: (Int) -> Unit,
) {
    val job = currentCoroutineContext()
    val staged = File.createTempFile("mdx-", ".wav", target.parentFile)
    try {
        val done = NativeMdx.separate(
            model.path, source.path, staged.path, frames, parameters.strengthPercent, parameters.overlapMs, parameters.threads,
            IntPredicate { percent ->
                if (job.isActive) { progress(percent); true } else false
            },
        )
        job.ensureActive()
        check(done && staged.length() == KaraokeAudio.HEADER_BYTES + frames * KaraokeAudio.BYTES_PER_FRAME)
        Os.rename(staged.path, target.path)
    } finally { staged.delete() }
}

@Keep
internal object NativeMdx {
    init { System.loadLibrary("lxmdx") }

    external fun separate(
        model: String, input: String, output: String, frames: Long,
        strengthPercent: Int, overlapMs: Int, threads: Int, progress: IntPredicate,
    ): Boolean
}
