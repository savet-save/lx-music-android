package cn.toside.music.nativeapp

import android.app.Application
import cn.toside.music.nativeapp.karaoke.KaraokePreparation
import cn.toside.music.nativeapp.utils.StoreHelper

class NativeApplication : Application() {
    internal val karaokePreparation by lazy { KaraokePreparation(this) }

    override fun onCreate() {
        super.onCreate()
        StoreHelper.init(this)
    }
}
