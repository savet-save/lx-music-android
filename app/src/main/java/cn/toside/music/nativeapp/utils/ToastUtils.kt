package cn.toside.music.nativeapp.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes

/** Process-wide toast replacement. Any thread may call it; only the latest queued request is shown. */
internal object ToastUtils {
    const val SHORT = Toast.LENGTH_SHORT
    const val LONG = Toast.LENGTH_LONG
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var generation = 0L
    private var pending: Runnable? = null
    private var toast: Toast? = null

    fun show(context: Context, message: CharSequence, duration: Int = SHORT) {
        val application = context.applicationContext
        val text = message.toString()
        postLatest {
            toast?.cancel()
            toast = Toast.makeText(application, text, duration).also { it.show() }
        }
    }
    fun show(context: Context, @StringRes message: Int, duration: Int = SHORT) = show(context, context.getString(message), duration)

    fun cancel() = postLatest { toast?.cancel(); toast = null }

    private fun postLatest(action: () -> Unit) {
        synchronized(lock) {
            val request = ++generation
            pending?.let(main::removeCallbacks)
            val update = Runnable {
                synchronized(lock) {
                    if (request != generation) return@Runnable
                    pending = null
                    action()
                }
            }
            pending = update
            main.post(update)
        }
    }
}
