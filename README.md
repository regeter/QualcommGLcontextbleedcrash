# Qualcomm Adreno OpenGL ES Shader Compiler Defect & Cross-Context Bleeding Repro

A standalone Android reproduction application designed to isolate and demonstrate a Qualcomm Adreno GPU shader compiler defect and a process-wide cross-context state bleeding issue.

**Direct Driver Assertion:**
```
Assertion failed: GVI && "cannot compute gv size for oob (no global info)"
```

---

## 1. Technical Overview

### The Driver Defect
On Qualcomm Adreno 6xx and 7xx series GPUs (e.g., Adreno 620 on Snapdragon 765G, Adreno 660 on Snapdragon 888, Adreno 730 on Snapdragon 8 Gen 1), two interrelated driver issues occur:

1. **Direct Compiler Assertion on Dynamic Uniform Indexing**:
   When an EGL context is created with robust buffer access enabled:
   ```c
   EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT = 0x30BF (value: 1)
   ```
   Qualcomm's proprietary LLVM-based shader compiler backend (`libllvm-qcom.so` / `libcompiler-qcom.so`) enables internal bounds-checking verification passes (`GVI` - Global Variable Indexing). When a vertex shader performs dynamic uniform array indexing via a vertex attribute (e.g. `uPalette[int(aIndex.r)]`), the compiler's bounds-checking pass hits an unhandled case, triggers an internal assertion, and aborts program linkage:
   ```
   Assertion failed: GVI && "cannot compute gv size for oob (no global info)"
   ```
   `GLES20.glLinkProgram` returns `0` (`GL_FALSE`).

2. **Process-Wide Cross-Context State Bleeding**:
   The `GVI` bounds-checking compiler flag inside Qualcomm's driver is stored in **process-wide global state** rather than being scoped to the lifecycle of the specific `EGLContext`.
   Once *any* thread in an Android process initializes an EGL context with `EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT`:
   - All subsequent or concurrent EGL contexts created in that same process—even standard contexts with **zero robust attributes requested**—inherit the active bounds-checking compiler state.
   - Any valid GLSL shader with dynamic uniform array indexing will fail linkage on the standard context with the same `GVI` assertion failure.

---

## 2. Zero External Dependencies

This repro application is 100% self-contained:
- Pure Android Framework APIs: `android.opengl.EGL14` and `android.opengl.GLES20`.
- No third-party rendering libraries, web engines, or external SDKs.
- Clean synthetic GLSL shaders.

---

## 3. How to Use the App (The 4 Test Buttons)

The UI provides 4 sequential test buttons:

```
+-----------------------------------------------------------------------------------------+
| Qualcomm GL Context Bleed Repro                                                         |
| Device: Google Pixel 5 | GPU: Adreno (TM) 620 | Driver: OpenGL ES 3.2                   |
+-----------------------------------------------------------------------------------------+
| [ 1. Standard Context (Dynamic Indexing) [Expect: PASS] ]                      (Green)  |
| [ 2. Robust Context (Safe Shader) [Expect: PASS] ]                             (Green)  |
| [ 3. Direct Driver Bug (Robust + Dynamic Indexing) [Expect: FAIL] ]             (Red)   |
| [ 4. Cross-Context Poisoning Test [Expect: BLEED / FAIL] ]                     (Orange) |
| [ Clear Shader Cache ]  [ Clear Log ]                                                   |
+-----------------------------------------------------------------------------------------+
| Log Output (Logcat tag: ADRENO_REPRO):                                                  |
| ...                                                                                     |
+-----------------------------------------------------------------------------------------+
```

### Button 1: `Standard Context (Dynamic Indexing)` [Expect: PASS]
* **Context**: Regular standard EGL context (`attribList = [0x3098, 2, 0x3038]`). Zero robust access attributes.
* **Shader**: Compiles and links a vertex shader with dynamic uniform array indexing:
  ```glsl
  vec4 color = uPaletteColors[int(aStyleAttr.r)];
  ```
* **Expected Outcome**: **PASSES (`GL_TRUE`)**.
* **Proves**: Dynamic uniform array indexing is fully valid OpenGL ES 2.0 / GLSL ES 1.00 code and compiles cleanly in isolation on standard OpenGL contexts.

---

### Button 2: `Robust Context (Safe Shader)` [Expect: PASS]
* **Context**: Robust access enabled:
  ```c
  attribList = [0x3098, 2, 0x30BF, 1, 0x31BD, 0x31BE, EGL_NONE]
  ```
* **Shader**: Compiles a safe, loop-unrolled shader with static constant indexing:
  ```glsl
  for (int i = 0; i < 16; i++) {
      if (idx == i) { color = uStyleColors[i]; }
  }
  ```
* **Expected Outcome**: **PASSES (`GL_TRUE`)**.
* **Proves**: `EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT` functions normally when shaders do not perform dynamic uniform array indexing.

---

### Button 3: `Direct Driver Bug (Robust + Dynamic Indexing)` [Expect: FAIL]
* **Context**: Robust access enabled (`0x30BF = 1`, `0x31BD = 0x31BE`).
* **Shader**: Compiles the dynamic uniform array indexing shader.
* **Expected Outcome**: **FAILS `glLinkProgram` with status = 0 (GL_FALSE)**.
* **Driver Assertion Captured**:
  ```
  I AdrenoGLES-0: Assertion failed: GVI && "cannot compute gv size for oob (no global info)"
  E ADRENO_REPRO: Dynamic Indexing Shader LINK FAILED (status=0) in 7 ms.
  InfoLog: Assertion failed: GVI && "cannot compute gv size for oob (no global info)"
  ```
* **Proves**: The direct root cause in Qualcomm Adreno's driver: Adreno's `GVI` bounds-checking compiler pass asserts on dynamic uniform array indexing.

---

### Button 4: `Cross-Context Poisoning Test` [Expect: BLEED / FAIL]
Demonstrates cross-context state bleeding between two independent contexts:

1. **Thread A**:
   - Creates a **ROBUST EGL Context** (`0x30BF = 1`).
   - Links the **Safe Shader** from Button 2.
   - **Result**: `SUCCESS` (100% clean).
   - Keeps the robust context alive.

2. **Thread B**:
   - Creates an independent **STANDARD EGL Context** on a separate thread.
   - Passes **ZERO robust attributes**:
     ```c
     attribList = [0x3098, 2, 0x3038]
     ```
   - Links the **Dynamic Indexing Shader** (the **exact same shader that passed in Button 1**).

3. **Expected Outcome**: **FAILS on Thread B's Standard Context (`GL_FALSE`)!**
   ```text
   Thread B: Creating STANDARD (non-robust) context while Robust context is ALIVE...
     Thread B attribs: [0x3098=2, 0x3038=EGL_NONE] (ZERO robust attributes!)
   Standard EGL context created successfully.
   [Thread B] Linking Dynamic Indexing Shader with fresh salt...

   Link Status : 0 (FAILED (GL_FALSE))
   Link Time   : 7 ms
   Link InfoLog: Assertion failed: GVI && "cannot compute gv size for oob (no global info)"
   Outcome     : FAILED

   [CRITICAL: CROSS-CONTEXT DRIVER BLEEDING CONFIRMED!]
   Context B (STANDARD) FAILED to link Dynamic Indexing Shader!
     Context B Attribs: [0x3098, 2, 0x3038] (NO ROBUST ACCESS)
     Assertion Error  : Assertion failed: GVI && "cannot compute gv size for oob (no global info)"
     Explanation: Robust access from Thread A permanently poisoned the process-wide Qualcomm compiler backend (libllvm-qcom.so)!
   ```
* **Proves**: Context A's robust access attribute permanently poisoned the process-wide Qualcomm compiler backend (`libllvm-qcom.so`), causing an independent context with zero robust attributes to fail linking valid GLSL shaders.

---

## 4. Technical Safeguards Implemented

1. **In-Memory Driver Shader Cache Bypassing**: Qualcomm's driver caches compiled program binaries in process memory. The test suite prepends a unique salt comment `// Salt: $nanoTime` to the vertex shader source on every compile pass, preventing the driver from reusing previously compiled in-memory binaries.
2. **Disk Shader Cache Disabling**: `ShaderCacheUtil.disableShaderDiskCache(context)` is invoked in `Application.attachBaseContext()` and before every test run. It deletes existing cache files under `code_cache/com.android.opengl.shaders_cache`, creates the directory, and touches `.nocache` inside it.
3. **Real-time Native Logcat Streaming**: A background thread monitors `logcat --pid=$myPid` and pipes native messages directly into the on-screen console (`[SYS] ...`), ensuring assertions printed by `libEGL` or `AdrenoGLES-0` are immediately visible.

---

## 5. Building and Running

### Prerequisites
- Physical Qualcomm device with an Adreno 6xx or 7xx GPU (e.g. Pixel 4, Pixel 5, Galaxy S20/S21/S22 Snapdragon, Xiaomi 10/11/12).
- Android SDK 26 to 34.

### Command Line Build & Run
```bash
./gradlew app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.fullparam.qualcommglcontextbleedcrash/.MainActivity
```

### Logcat Filter
```bash
adb logcat -s ADRENO_REPRO:V AdrenoGLES-0:V libEGL:V
```
