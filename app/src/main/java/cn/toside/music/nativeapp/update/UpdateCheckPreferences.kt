package cn.toside.music.nativeapp.update

import android.content.Context

internal class UpdateCheckPreferences(context: Context, name: String = "app-settings") : UpdateCheckStore {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun read(): UpdateCheckHistory {
        fun timestamp(key: String) = (preferences.all[key] as? String)?.toLongOrNull() ?: 0
        // The legacy lastCheck was recorded before the request, so it cannot prove a successful check.
        return UpdateCheckHistory(timestamp("version.lastSuccessfulCheck"), timestamp("version.lastFailedCheck"))
    }

    override fun write(history: UpdateCheckHistory) {
        preferences.edit()
            .putString("version.lastSuccessfulCheck", history.lastSuccessAt.toString())
            .putString("version.lastFailedCheck", history.lastFailureAt.toString())
            .remove("version.lastCheck")
            .apply()
    }
}
