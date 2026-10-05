package com.fullparam.qualcommglcontextbleedcrash

import android.app.Application
import android.content.Context
import android.util.Log

class AdrenoReproApplication : Application() {

    companion object {
        private const val TAG = "ADRENO_REPRO"
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Log.i(TAG, "AdrenoReproApplication.attachBaseContext called. Disabling shader disk cache...")
        val messages = ShaderCacheUtil.disableShaderDiskCache(this)
        for (msg in messages) {
            Log.i(TAG, "Cache init: $msg")
        }
    }
}
