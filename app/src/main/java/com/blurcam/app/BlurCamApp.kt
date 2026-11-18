package com.blurcam.app

import android.app.Application
import com.blurcam.app.pipeline.MlKitGate

class BlurCamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MlKitGate.init(this)
    }
}
