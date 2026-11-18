package cn.toside.music.nativeapp

import android.app.Application
import cn.toside.music.nativeapp.utils.StoreHelper

class NativeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StoreHelper.init(this)
    }
}
