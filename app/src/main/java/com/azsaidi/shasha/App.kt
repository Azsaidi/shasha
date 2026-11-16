package com.azsaidi.shasha

import android.app.Application
import com.azsaidi.shasha.data.Store

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}
