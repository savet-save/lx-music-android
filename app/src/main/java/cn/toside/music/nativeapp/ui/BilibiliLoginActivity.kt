package cn.toside.music.nativeapp.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.widget.*
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.online.BilibiliApi
import cn.toside.music.nativeapp.online.BilibiliCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BilibiliLoginActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val credentialsMutex=Mutex()
    private var operation:Job?=null
    private var verifySession:suspend (String)->String = { BilibiliApi(this,it).account() }
    override fun attachBaseContext(base: Context) = super.attachBaseContext(UiPreferences.context(base))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = (20 * resources.displayMetrics.density).toInt(); setPadding(p,p,p,p) }
        panel.addView(TextView(this).apply { text = getString(R.string.bili_login); textSize = 24f })
        panel.addView(TextView(this).apply { text = getString(R.string.bili_login_hint); setPadding(0,24,0,24) })
        val status = TextView(this).also { panel.addView(it) }
        val input = EditText(this).apply {
            tag = "bili-token"; hint = "SESSDATA"; contentDescription = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true); isSaveEnabled = false; importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            filters = arrayOf(android.text.InputFilter.LengthFilter(4105))
        }.also { panel.addView(it) }
        val save = Button(this).apply { text = getString(R.string.bili_login_save); tag = "bili-token-save" }.also { panel.addView(it) }
        save.setOnClickListener {
            val token = input.text.toString().trim().removePrefix("SESSDATA=")
            save.isEnabled = false; status.text = getString(R.string.bili_checking)
            operation?.cancel()
            operation=scope.launch {
                try {
                    val name = verifySession(token)
                    withContext(Dispatchers.IO) { credentialsMutex.withLock {
                        currentCoroutineContext().ensureActive()
                        BilibiliCredentials(this@BilibiliLoginActivity).save(token)
                    } }
                    input.text.clear(); status.text = getString(R.string.bili_logged_in, name)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { status.text = e.localizedMessage }
                finally { if(currentCoroutineContext().isActive) save.isEnabled = true }
            }
        }
        panel.addView(Button(this).apply { text = getString(R.string.bili_logout); tag = "bili-token-clear"; setOnClickListener {
            operation?.cancel(); save.isEnabled=false
            operation=scope.launch {
                try {
                    withContext(Dispatchers.IO) { credentialsMutex.withLock { BilibiliCredentials(this@BilibiliLoginActivity).clear() } }
                    input.text.clear(); status.text=getString(R.string.bili_logged_out)
                } catch(e:CancellationException) { throw e }
                catch(e:Exception) { status.text=e.localizedMessage }
                finally { if(currentCoroutineContext().isActive) save.isEnabled=true }
            }
        } })
        panel.addView(Button(this).apply { text = getString(android.R.string.cancel); setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(panel) })
        operation=scope.launch {
            if (withContext(Dispatchers.IO) { BilibiliCredentials(this@BilibiliLoginActivity).load().isNotBlank() }) {
                try { status.text = getString(R.string.bili_logged_in, BilibiliApi(this@BilibiliLoginActivity).account()) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { status.text = e.localizedMessage }
            } else status.text = getString(R.string.bili_logged_out)
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
