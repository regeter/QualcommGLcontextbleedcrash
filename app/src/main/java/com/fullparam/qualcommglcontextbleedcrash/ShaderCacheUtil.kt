package com.fullparam.qualcommglcontextbleedcrash

import android.content.Context
import android.util.Log
import java.io.File

object ShaderCacheUtil {
    private const val TAG = "ADRENO_REPRO"
    private const val SHADER_CACHE_NAME = "com.android.opengl.shaders_cache"
    private const val NO_CACHE_NAME = ".nocache"

    /**
     * Disables the Android OpenGL shader disk cache by targeting:
     * - File(codeCacheDir, "com.android.opengl.shaders_cache")
     * - File(createDeviceProtectedStorageContext().codeCacheDir, "com.android.opengl.shaders_cache")
     *
     * If the target exists, it is deleted. Then the directory is created and an empty file
     * named ".nocache" is created inside it to prevent shader binary caching.
     */
    fun disableShaderDiskCache(context: Context): List<String> {
        val messages = mutableListOf<String>()
        val targets = mutableListOf<File>()

        try {
            targets.add(File(context.codeCacheDir, SHADER_CACHE_NAME))
        } catch (e: Exception) {
            val err = "Failed to access context.codeCacheDir: ${e.message}"
            Log.e(TAG, err, e)
            messages.add(err)
        }

        try {
            val deviceContext = context.createDeviceProtectedStorageContext()
            targets.add(File(deviceContext.codeCacheDir, SHADER_CACHE_NAME))
        } catch (e: Exception) {
            val err = "Failed to access deviceProtectedStorageContext.codeCacheDir: ${e.message}"
            Log.e(TAG, err, e)
            messages.add(err)
        }

        for (target in targets) {
            try {
                if (target.exists()) {
                    val deleted = if (target.isDirectory) {
                        target.deleteRecursively()
                    } else {
                        target.delete()
                    }
                    val msg = "Existing shader cache at [${target.absolutePath}] deleted: $deleted"
                    Log.i(TAG, msg)
                    messages.add(msg)
                }

                val dirCreated = target.mkdirs() || target.isDirectory
                val noCacheFile = File(target, NO_CACHE_NAME)
                val touched = noCacheFile.exists() || noCacheFile.createNewFile()
                val msg = "Shader cache directory [${target.absolutePath}] created=$dirCreated, $NO_CACHE_NAME touched=$touched"
                Log.i(TAG, msg)
                messages.add(msg)
            } catch (e: Exception) {
                val err = "Error handling shader cache target [${target.absolutePath}]: ${e.message}"
                Log.e(TAG, err, e)
                messages.add(err)
            }
        }

        return messages
    }
}
