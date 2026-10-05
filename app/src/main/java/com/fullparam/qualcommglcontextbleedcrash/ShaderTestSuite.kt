package com.fullparam.qualcommglcontextbleedcrash

import android.opengl.GLES20
import android.os.SystemClock
import android.util.Log

object ShaderTestSuite {

    private const val TAG = "ADRENO_REPRO"

    /**
     * Synthetic vertex shader with dynamic uniform array indexing.
     * When compiled under EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT on Qualcomm Adreno 6xx/7xx,
     * triggers: Assertion failed: GVI && "cannot compute gv size for oob (no global info)"
     */
    const val DYNAMIC_INDEXING_VERTEX_SHADER = """precision highp float;
varying vec4 vColor;

#define MAX_PALETTE_ENTRIES 16
#define SCALE_FACTOR 4.0

uniform mat4 uMVPMatrix;
uniform vec4 uTransformOffset;
uniform float uHeightScale;
uniform vec4 uPaletteColors[MAX_PALETTE_ENTRIES];
uniform float uZoomLevel;
uniform float uAlphaFactor;
uniform float uDepthBias;
uniform float uFogStart;
uniform float uFogEnd;

attribute vec4 aPosition;
attribute vec4 aStyleAttr;

void main() {
  vec4 color = uPaletteColors[int(aStyleAttr.r)];
  float minThreshold = aStyleAttr.b / SCALE_FACTOR;
  float maxThreshold = aStyleAttr.a / SCALE_FACTOR;
  bool skip = color.a == 0.0 || uZoomLevel < minThreshold || uZoomLevel > maxThreshold;
  if (skip) {
    gl_Position = vec4(0.0, 0.0, 0.0, 0.0);
    return;
  }
  vec3 pos = aPosition.xyz;
  pos = (pos * uTransformOffset.w) + uTransformOffset.xyz;
  vec4 clipPos = uMVPMatrix * vec4(pos.xy, pos.z * uHeightScale, 1.0);
  gl_Position = clipPos + vec4(0.0, 0.0, uDepthBias, 0.0);
  vColor = color * vec2(aStyleAttr.g / 255.0, uAlphaFactor).xxxy;
  vColor.r = min(vColor.r, 1.0);
  vColor.g = min(vColor.g, 1.0);
  vColor.b = min(vColor.b, 1.0);

  float fade = (1.0 - smoothstep(uFogStart, uFogEnd, clipPos.z / clipPos.w));
  vColor.a = vColor.a * fade;
}"""

    const val DYNAMIC_INDEXING_FRAGMENT_SHADER = """precision highp float;
varying vec4 vColor;

void main() {
  gl_FragColor = vColor;
}"""

    // Benchmark Variant A (16-slot Dynamic Uniform Array Indexing)
    const val VERTEX_SHADER_A = """precision highp float;
uniform mat4 uMVPMatrix;
uniform vec4 uStyleColors[16];
attribute vec4 aPosition;
attribute vec4 aStyleInfo; // aStyleInfo.r holds float index (e.g., 0.0 .. 15.0)
varying vec4 vColor;
void main() {
    int idx = int(aStyleInfo.r);
    // DYNAMIC UNIFORM ARRAY ACCESS (Triggers Adreno GVI oob assertion)
    vColor = uStyleColors[idx];
    gl_Position = uMVPMatrix * aPosition;
}"""

    // Benchmark Variant B (64-slot Dynamic Uniform Array Indexing)
    const val VERTEX_SHADER_B = """precision highp float;
uniform mat4 uMVPMatrix;
uniform ivec4 uStyleColors[64];
attribute vec4 aPosition;
attribute vec4 aStyleInfo;
varying vec4 vColor;
void main() {
    int idx = int(clamp(aStyleInfo.r, 0.0, 63.0));
    ivec4 colorEntry = uStyleColors[idx];
    vColor = vec4(colorEntry) / 255.0;
    gl_Position = uMVPMatrix * aPosition;
}"""

    // Workaround Variant C (Loop Unrolled with Static Constants)
    const val VERTEX_SHADER_C = """precision highp float;
uniform mat4 uMVPMatrix;
uniform vec4 uStyleColors[16];
attribute vec4 aPosition;
attribute vec4 aStyleInfo;
varying vec4 vColor;
void main() {
    int idx = int(aStyleInfo.r);
    vec4 color = vec4(0.0);
    for (int i = 0; i < 16; i++) {
        if (idx == i) {
            color = uStyleColors[i];
        }
    }
    vColor = color;
    gl_Position = uMVPMatrix * aPosition;
}"""

    // Common Fragment Shader
    const val FRAGMENT_SHADER = """precision mediump float;
varying vec4 vColor;
void main() {
    gl_FragColor = vColor;
}"""

    data class ShaderResult(
        val variantName: String,
        val description: String,
        val vsCompileSuccess: Boolean,
        val vsInfoLog: String,
        val fsCompileSuccess: Boolean,
        val fsInfoLog: String,
        val linkStatus: Int,
        val linkSuccess: Boolean,
        val linkInfoLog: String,
        val linkDurationMs: Long,
        val glRenderer: String,
        val glVersion: String,
        val glVendor: String
    ) {
        fun formattedOutput(): String {
            val statusStr = if (linkSuccess) "SUCCESS (GL_TRUE)" else "FAILED (GL_FALSE)"
            val resultSummary = if (linkSuccess) "PASSED" else "FAILED"
            val sb = StringBuilder()
            sb.appendLine("--- $variantName ($description) ---")
            sb.appendLine("  GL_RENDERER : $glRenderer")
            sb.appendLine("  GL_VERSION  : $glVersion")
            sb.appendLine("  GL_VENDOR   : $glVendor")
            sb.appendLine("  VS Compile  : ${if (vsCompileSuccess) "SUCCESS" else "FAILED"} ${if (vsInfoLog.isNotBlank()) "(log: $vsInfoLog)" else ""}")
            sb.appendLine("  FS Compile  : ${if (fsCompileSuccess) "SUCCESS" else "FAILED"} ${if (fsInfoLog.isNotBlank()) "(log: $fsInfoLog)" else ""}")
            sb.appendLine("  Link Status : $linkStatus ($statusStr)")
            sb.appendLine("  Link Time   : $linkDurationMs ms")
            if (linkInfoLog.isNotBlank()) {
                sb.appendLine("  Link InfoLog: $linkInfoLog")
            } else {
                sb.appendLine("  Link InfoLog: <empty>")
            }
            sb.appendLine("  Outcome     : $resultSummary")
            return sb.toString().trimEnd()
        }
    }

    /**
     * Compiles and links a single shader variant.
     * Captures link status, program info log, timing, and GL strings.
     */
    fun testShader(
        variantName: String,
        description: String,
        vertexSource: String,
        fragmentSource: String = FRAGMENT_SHADER
    ): ShaderResult {
        val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "UNKNOWN"
        val version = GLES20.glGetString(GLES20.GL_VERSION) ?: "UNKNOWN"
        val vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "UNKNOWN"

        Log.i(TAG, "Testing $variantName ($description)...")
        Log.i(TAG, "GL_RENDERER: $renderer | GL_VERSION: $version | GL_VENDOR: $vendor")

        // Vertex Shader
        val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
        GLES20.glShaderSource(vs, vertexSource)
        GLES20.glCompileShader(vs)
        val vsStatus = IntArray(1)
        GLES20.glGetShaderiv(vs, GLES20.GL_COMPILE_STATUS, vsStatus, 0)
        val vsCompileSuccess = vsStatus[0] == GLES20.GL_TRUE
        val vsInfoLog = GLES20.glGetShaderInfoLog(vs) ?: ""
        if (!vsCompileSuccess) {
            Log.e(TAG, "$variantName VS compile failed: $vsInfoLog")
        }

        // Fragment Shader
        val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
        GLES20.glShaderSource(fs, fragmentSource)
        GLES20.glCompileShader(fs)
        val fsStatus = IntArray(1)
        GLES20.glGetShaderiv(fs, GLES20.GL_COMPILE_STATUS, fsStatus, 0)
        val fsCompileSuccess = fsStatus[0] == GLES20.GL_TRUE
        val fsInfoLog = GLES20.glGetShaderInfoLog(fs) ?: ""
        if (!fsCompileSuccess) {
            Log.e(TAG, "$variantName FS compile failed: $fsInfoLog")
        }

        // Program Link
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)

        Log.i(TAG, "Calling glLinkProgram for $variantName...")
        val startTime = SystemClock.elapsedRealtime()
        GLES20.glLinkProgram(program)
        val durationMs = SystemClock.elapsedRealtime() - startTime

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        val linkSuccess = linkStatus[0] == GLES20.GL_TRUE
        val linkInfoLog = GLES20.glGetProgramInfoLog(program) ?: ""

        if (linkSuccess) {
            Log.i(TAG, "$variantName LINK SUCCESS in $durationMs ms. InfoLog: $linkInfoLog")
        } else {
            Log.e(TAG, "$variantName LINK FAILED (status=${linkStatus[0]}) in $durationMs ms. InfoLog: $linkInfoLog")
        }

        // Cleanup GL objects
        GLES20.glDetachShader(program, vs)
        GLES20.glDetachShader(program, fs)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        GLES20.glDeleteProgram(program)

        val result = ShaderResult(
            variantName = variantName,
            description = description,
            vsCompileSuccess = vsCompileSuccess,
            vsInfoLog = vsInfoLog,
            fsCompileSuccess = fsCompileSuccess,
            fsInfoLog = fsInfoLog,
            linkStatus = linkStatus[0],
            linkSuccess = linkSuccess,
            linkInfoLog = linkInfoLog,
            linkDurationMs = durationMs,
            glRenderer = renderer,
            glVersion = version,
            glVendor = vendor
        )

        Log.i(TAG, result.formattedOutput())
        return result
    }

    /**
     * Tests the dynamic indexing shader known to trigger Adreno GVI assertion.
     * Uses a unique salt comment to ensure the in-memory driver shader cache is bypassed.
     */
    fun testDynamicIndexingShader(salt: String = System.nanoTime().toString()): ShaderResult {
        val saltedVertex = "// Salt: $salt\n$DYNAMIC_INDEXING_VERTEX_SHADER"
        return testShader(
            variantName = "Dynamic Indexing Shader",
            description = "Dynamic uniform array indexing via attribute",
            vertexSource = saltedVertex,
            fragmentSource = DYNAMIC_INDEXING_FRAGMENT_SHADER
        )
    }

    /**
     * Executes test for Dynamic Indexing Shader, and Variants A, B, and C in order.
     * Uses a unique salt to force Qualcomm compiler to compile fresh without in-memory caching.
     */
    fun runAllVariants(salt: String = System.nanoTime().toString()): List<ShaderResult> {
        val results = mutableListOf<ShaderResult>()

        results.add(
            testDynamicIndexingShader(salt = salt)
        )

        results.add(
            testShader(
                variantName = "Shader Variant A",
                description = "16-slot Dynamic Uniform Indexing",
                vertexSource = "// Salt: $salt\n$VERTEX_SHADER_A"
            )
        )

        results.add(
            testShader(
                variantName = "Shader Variant B",
                description = "64-slot Dynamic Uniform Indexing",
                vertexSource = "// Salt: $salt\n$VERTEX_SHADER_B"
            )
        )

        results.add(
            testShader(
                variantName = "Shader Variant C",
                description = "Workaround - Loop Unrolled with Static Constants",
                vertexSource = "// Salt: $salt\n$VERTEX_SHADER_C"
            )
        )

        return results
    }
}
