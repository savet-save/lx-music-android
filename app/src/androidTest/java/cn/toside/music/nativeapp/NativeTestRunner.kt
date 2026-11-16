package cn.toside.music.nativeapp

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Ordinary feature tests start after onboarding; PactTest exercises first use explicitly. */
class NativeTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        arguments.putString("listener", listOfNotNull(arguments.getString("listener"), HomePageTestListener::class.java.name).joinToString(","))
        super.onCreate(arguments)
    }
    private var originalAgreement: Boolean? = null
    private var prepared = false
    private var originalAutoCheck: Boolean? = null
    private var originalLanguage: String? = null
    override fun onStart() {
        val preferences = targetContext.getSharedPreferences("app-settings", 0)
        originalAgreement = preferences.all["common.isAgreePact"] as? Boolean
        originalAutoCheck = preferences.all["version.autoCheck"] as? Boolean
        originalLanguage = preferences.all["common.langId"] as? String
        check(preferences.edit().putBoolean("common.isAgreePact", true).putBoolean("version.autoCheck", false).putString("common.langId", "zh-CN").commit())
        prepared = true
        super.onStart()
    }
    override fun finish(resultCode: Int, results: Bundle?) {
        if (prepared) {
            val preferences = targetContext.getSharedPreferences("app-settings", 0)
            preferences.edit().apply {
                originalAgreement?.let { putBoolean("common.isAgreePact", it) } ?: remove("common.isAgreePact")
                originalAutoCheck?.let { putBoolean("version.autoCheck", it) } ?: remove("version.autoCheck")
                originalLanguage?.let { putString("common.langId", it) } ?: remove("common.langId")
            }.commit()
        }
        super.finish(resultCode, results)
    }
}
