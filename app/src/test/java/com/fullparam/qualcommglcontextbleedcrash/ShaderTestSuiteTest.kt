package com.fullparam.qualcommglcontextbleedcrash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaderTestSuiteTest {

    @Test
    fun testShaderConstants() {
        assertEquals(0x3098, EglEngine.EGL_CONTEXT_CLIENT_VERSION)
        assertEquals(0x30BF, EglEngine.EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT)
        assertEquals(0x31BD, EglEngine.EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_EXT)
        assertEquals(0x31BE, EglEngine.EGL_LOSE_CONTEXT_ON_RESET_EXT)
    }

    @Test
    fun testShaderSources() {
        // Dynamic Indexing Shader
        assertTrue(ShaderTestSuite.DYNAMIC_INDEXING_VERTEX_SHADER.contains("vec4 color = uPaletteColors[int(aStyleAttr.r)];"))
        assertTrue(ShaderTestSuite.DYNAMIC_INDEXING_VERTEX_SHADER.contains("#define MAX_PALETTE_ENTRIES 16"))
        assertTrue(ShaderTestSuite.DYNAMIC_INDEXING_VERTEX_SHADER.contains("uniform vec4 uPaletteColors[MAX_PALETTE_ENTRIES];"))
        assertTrue(ShaderTestSuite.DYNAMIC_INDEXING_VERTEX_SHADER.contains("vColor = color * vec2(aStyleAttr.g / 255.0, uAlphaFactor).xxxy;"))

        // Variant A
        assertTrue(ShaderTestSuite.VERTEX_SHADER_A.contains("uniform vec4 uStyleColors[16];"))
        assertTrue(ShaderTestSuite.VERTEX_SHADER_A.contains("int idx = int(aStyleInfo.r);"))
        assertTrue(ShaderTestSuite.VERTEX_SHADER_A.contains("vColor = uStyleColors[idx];"))

        // Variant B
        assertTrue(ShaderTestSuite.VERTEX_SHADER_B.contains("uniform ivec4 uStyleColors[64];"))
        assertTrue(ShaderTestSuite.VERTEX_SHADER_B.contains("int idx = int(clamp(aStyleInfo.r, 0.0, 63.0));"))
        assertTrue(ShaderTestSuite.VERTEX_SHADER_B.contains("vColor = vec4(colorEntry) / 255.0;"))

        // Variant C
        assertTrue(ShaderTestSuite.VERTEX_SHADER_C.contains("for (int i = 0; i < 16; i++)"))
        assertTrue(ShaderTestSuite.VERTEX_SHADER_C.contains("if (idx == i)"))
        assertTrue(ShaderTestSuite.VERTEX_SHADER_C.contains("color = uStyleColors[i];"))

        // Fragment Shaders
        assertTrue(ShaderTestSuite.FRAGMENT_SHADER.contains("gl_FragColor = vColor;"))
        assertTrue(ShaderTestSuite.DYNAMIC_INDEXING_FRAGMENT_SHADER.contains("gl_FragColor = vColor;"))
    }
}
