package com.fullparam.qualcommglcontextbleedcrash

import android.content.Context
import android.util.Log
import java.io.File

object ShaderCacheUtil {
    private const val TAG = "ADRENO_REPRO"
    private const val SHADER_CACHE_NAME = "com.android.opengl.shaders_cache"

    /**
     * Clears the Android OpenGL shader disk cache by deleting:
     * - File(codeCacheDir, "com.android.opengl.shaders_cache")
     * - File(createDeviceProtectedStorageContext().codeCacheDir, "com.android.opengl.shaders_cache")
     *
     * Note: Do not replace the cache file path with a directory, as Android's FileBlobCache
     * (frameworks/native/opengl/libs/EGL/egl_cache.cpp) attempts to open() and mmap() this path as
     * a regular file, which triggers an SELinux avc: denied { map } on directories. Per-compile
     * unique salt comments in ShaderTestSuite already guarantee cache misses.
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
                } else {
                    val msg = "No existing shader cache at [${target.absolutePath}]"
                    Log.d(TAG, msg)
                    messages.add(msg)
                }
            } catch (e: Exception) {
                val err = "Error handling shader cache target [${target.absolutePath}]: ${e.message}"
                Log.e(TAG, err, e)
                messages.add(err)
            }
        }

        return messages
    }
}
