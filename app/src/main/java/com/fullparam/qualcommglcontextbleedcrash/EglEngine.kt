package com.fullparam.qualcommglcontextbleedcrash

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log

class EglEngine {

    companion object {
        private const val TAG = "ADRENO_REPRO"

        // EGL context attribute constants
        const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
        const val EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT = 0x30BF
        const val EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_EXT = 0x31BD
        const val EGL_LOSE_CONTEXT_ON_RESET_EXT = 0x31BE

        fun eglErrorToString(error: Int): String {
            return when (error) {
                EGL14.EGL_SUCCESS -> "EGL_SUCCESS (0x3000)"
                EGL14.EGL_NOT_INITIALIZED -> "EGL_NOT_INITIALIZED (0x3001)"
                EGL14.EGL_BAD_ACCESS -> "EGL_BAD_ACCESS (0x3002)"
                EGL14.EGL_BAD_ALLOC -> "EGL_BAD_ALLOC (0x3003)"
                EGL14.EGL_BAD_ATTRIBUTE -> "EGL_BAD_ATTRIBUTE (0x3004)"
                EGL14.EGL_BAD_CONFIG -> "EGL_BAD_CONFIG (0x3005)"
                EGL14.EGL_BAD_CONTEXT -> "EGL_BAD_CONTEXT (0x3006)"
                EGL14.EGL_BAD_CURRENT_SURFACE -> "EGL_BAD_CURRENT_SURFACE (0x3007)"
                EGL14.EGL_BAD_DISPLAY -> "EGL_BAD_DISPLAY (0x3008)"
                EGL14.EGL_BAD_MATCH -> "EGL_BAD_MATCH (0x3009)"
                EGL14.EGL_BAD_NATIVE_PIXMAP -> "EGL_BAD_NATIVE_PIXMAP (0x300A)"
                EGL14.EGL_BAD_NATIVE_WINDOW -> "EGL_BAD_NATIVE_WINDOW (0x300B)"
                EGL14.EGL_BAD_PARAMETER -> "EGL_BAD_PARAMETER (0x300C)"
                EGL14.EGL_BAD_SURFACE -> "EGL_BAD_SURFACE (0x300D)"
                EGL14.EGL_CONTEXT_LOST -> "EGL_CONTEXT_LOST (0x300E)"
                else -> "UNKNOWN EGL ERROR (0x${Integer.toHexString(error)})"
            }
        }
    }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglConfig: EGLConfig? = null
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT

    /**
     * Initializes EGL display, selects RGB_888, PBUFFER, OPENGL_ES2 config,
     * and creates a 1x1 pbuffer surface.
     */
    fun init() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            destroy()
        }

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            val err = EGL14.eglGetError()
            throw RuntimeException("eglGetDisplay failed: ${eglErrorToString(err)}")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            val err = EGL14.eglGetError()
            throw RuntimeException("eglInitialize failed: ${eglErrorToString(err)}")
        }
        Log.i(TAG, "EGL initialized: version ${version[0]}.${version[1]}")

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] == 0 || configs[0] == null
        ) {
            val err = EGL14.eglGetError()
            throw RuntimeException("eglChooseConfig failed: ${eglErrorToString(err)}")
        }
        eglConfig = configs[0]

        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            val err = EGL14.eglGetError()
            throw RuntimeException("eglCreatePbufferSurface failed: ${eglErrorToString(err)}")
        }
        Log.i(TAG, "EGL 1x1 Pbuffer surface created successfully.")
    }

    fun getEglExtensions(): String {
        return if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglQueryString(eglDisplay, EGL14.EGL_EXTENSIONS) ?: ""
        } else {
            ""
        }
    }

    fun isRobustnessExtensionSupported(): Boolean {
        val exts = getEglExtensions()
        return exts.contains("EGL_EXT_create_context_robustness") || exts.contains("EGL_KHR_create_context")
    }

    /**
     * Creates an EGL context with optional robust buffer access.
     * When enableRobustness is true:
     * Appends 0x30BF (EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT) = 1,
     * and 0x31BD (EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_EXT) = 0x31BE (EGL_LOSE_CONTEXT_ON_RESET_EXT).
     */
    fun createContext(enableRobustness: Boolean, clientVersion: Int = 2) {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglConfig == null) {
            init()
        }

        destroyContext()

        if (!enableRobustness) {
            val attribList = intArrayOf(
                EGL_CONTEXT_CLIENT_VERSION, clientVersion,
                EGL14.EGL_NONE
            )
            val attribStr = attribList.joinToString(", ") { "0x" + Integer.toHexString(it) }
            Log.i(TAG, "Creating standard EGL context (enableRobustness=false, clientVersion=$clientVersion, attribs=[$attribStr])...")
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, attribList, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                val err = EGL14.eglGetError()
                throw RuntimeException("eglCreateContext failed for standard context: ${eglErrorToString(err)}")
            }
            Log.i(TAG, "Standard EGL context created successfully.")
            return
        }

        // Robust context: Try primary specification first (0x30BF=1, 0x31BD=0x31BE), then fallback to 0x30BF=1
        val candidates = listOf(
            Pair(
                "Primary [clientVersion=$clientVersion, 0x30BF=1, 0x31BD=0x31BE]",
                intArrayOf(
                    EGL_CONTEXT_CLIENT_VERSION, clientVersion,
                    EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT, 1,
                    EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_EXT, EGL_LOSE_CONTEXT_ON_RESET_EXT,
                    EGL14.EGL_NONE
                )
            ),
            Pair(
                "Fallback [clientVersion=$clientVersion, 0x30BF=1]",
                intArrayOf(
                    EGL_CONTEXT_CLIENT_VERSION, clientVersion,
                    EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT, 1,
                    EGL14.EGL_NONE
                )
            )
        )

        var lastError = EGL14.EGL_SUCCESS
        for ((desc, attribList) in candidates) {
            val attribStr = attribList.joinToString(", ") { "0x" + Integer.toHexString(it) }
            Log.i(TAG, "Attempting robust EGL context creation: $desc (attribs=[$attribStr])...")
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, attribList, 0)
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                Log.i(TAG, "Robust EGL context created successfully using $desc.")
                return
            }
            lastError = EGL14.eglGetError()
            Log.w(TAG, "Robust context creation attempt failed ($desc): ${eglErrorToString(lastError)}")
        }

        val extensions = getEglExtensions()
        val hasRobustExt = isRobustnessExtensionSupported()
        val errorDesc = eglErrorToString(lastError)
        val failureDetail = buildString {
            appendLine("eglCreateContext failed for robust context: $errorDesc")
            appendLine("EGL_EXT_create_context_robustness supported: $hasRobustExt")
            if (!hasRobustExt) {
                appendLine("WARNING: Current driver does not advertise 'EGL_EXT_create_context_robustness'.")
                appendLine("On emulators or host GPU translators, robust access attributes may be rejected with EGL_BAD_ATTRIBUTE (0x3004).")
                appendLine("To reproduce the Qualcomm Adreno bug, run this on a physical device with an Adreno 6xx/7xx GPU.")
            }
            append("Available EGL Extensions: $extensions")
        }
        throw RuntimeException(failureDetail)
    }

    /**
     * Makes the current EGL display, pbuffer surface, and context current on the calling thread.
     */
    fun makeCurrent() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE || eglContext == EGL14.EGL_NO_CONTEXT) {
            throw IllegalStateException("EGL display, surface, or context is not ready for makeCurrent")
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            val err = EGL14.eglGetError()
            throw RuntimeException("eglMakeCurrent failed: ${eglErrorToString(err)}")
        }
        Log.i(TAG, "eglMakeCurrent succeeded.")
    }

    /**
     * Unbinds and destroys the current EGLContext while leaving display and surface intact.
     */
    fun destroyContext() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
            Log.i(TAG, "EGL context destroyed.")
        }
    }

    /**
     * Tears down the context, surface, and terminates the EGL display.
     */
    fun destroy() {
        destroyContext()
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
                Log.i(TAG, "EGL surface destroyed.")
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
            Log.i(TAG, "EGL terminated.")
        }
        eglConfig = null
    }
}
