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
   Qualcomm's proprietary LLVM-based shader compiler backend (`/vendor/lib64/egl/libGLESv2_adreno.so` / `libllvm-glnext.so`) enables internal bounds-checking verification passes (`GVI` - Global Variable Indexing). When a vertex shader performs dynamic uniform array indexing via a vertex attribute (e.g. `uPalette[int(aIndex.r)]`), the compiler's bounds-checking pass hits an unhandled case, triggers an internal assertion, and aborts program linkage:
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

### Button 3: `Direct Driver Bug (Robust + Dynamic Indexing)` [Expect: FAIL on Qualcomm]
* **Context**: Robust access enabled (`0x30BF = 1`, `0x31BD = 0x31BE`).
* **Shader**: Compiles the dynamic uniform array indexing shader.
* **Outcome on Qualcomm Adreno (Pixel 5, Adreno 620)**: **FAILS `glLinkProgram` with status = 0 (`GL_FALSE`)**.
* **Driver Assertion Captured**:
  ```
  I AdrenoGLES-0: Assertion failed: GVI && "cannot compute gv size for oob (no global info)"
  E ADRENO_REPRO: Dynamic Indexing Shader LINK FAILED (status=0) in 7 ms.
  InfoLog: Assertion failed: GVI && "cannot compute gv size for oob (no global info)"
  ```
* **Outcome on Control Device (Pixel 8 Pro, ARM Mali-G715)**: **PASSES (`GL_TRUE`) in 1 ms**.
* **Proves**:
  1. Dynamic uniform array indexing under `EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT` is 100% spec-compliant OpenGL ES code (`GL_KHR_robust_buffer_access_behavior` / `GL_EXT_robustness`). ARM Mali-G715 properly compiles and links it with zero warnings.
  2. The failure on Adreno is exclusively a Qualcomm LLVM compiler crash: Qualcomm's internal bounds-checking pass (`GVI`) hits an unhandled assertion rather than emitting valid bounds clamping.

---

### Button 4: `Cross-Context Poisoning Test` [Expect: BLEED / FAIL]
Demonstrates cross-context state bleeding between two independent contexts in a self-contained 3-step sequence:

1. **Step 1 (Thread B Pre-Check - Standard Context)**:
   - Creates a **STANDARD EGL Context** (`attribList = [0x3098, 2, 0x3038]`).
   - Links the **Dynamic Indexing Shader**.
   - **Result**: `SUCCESS (GL_TRUE)` (Proves it works before any robust context is created).

2. **Step 2 (Thread A - Robust Context)**:
   - Creates a **ROBUST EGL Context** (`0x30BF = 1`).
   - Links the **Safe Shader** from Button 2.
   - **Result**: `SUCCESS (GL_TRUE)` (100% clean).
   - Keeps the robust context alive.

3. **Step 3 (Thread B Post-Check - Standard Context)**:
   - Creates another **STANDARD EGL Context** with **ZERO robust attributes**:
     ```c
     attribList = [0x3098, 2, 0x3038]
     ```
   - Links the **Dynamic Indexing Shader** (the exact same shader that passed in Step 1).
   - **Result**: **FAILS on Thread B's Standard Context (`GL_FALSE`)!**
   - **Driver Assertion**:
     ```text
     Assertion failed: GVI && "cannot compute gv size for oob (no global info)"
     ```
   - **Proves**: Context A's robust access attribute permanently poisoned the process-wide Qualcomm compiler backend (`/vendor/lib64/egl/libGLESv2_adreno.so` / `libllvm-glnext.so`), causing an independent context with zero robust attributes to fail linking valid GLSL shaders.

---

## 4. Technical Safeguards Implemented

1. **In-Memory Driver Shader Cache Bypassing**: Qualcomm's driver caches compiled program binaries in process memory. The test suite prepends a unique salt comment `// Salt: $nanoTime` to the vertex shader source on every compile pass, preventing the driver from reusing previously compiled in-memory binaries.
2. **Disk Shader Cache Disabling**: `ShaderCacheUtil.disableShaderDiskCache(context)` deletes existing cache files under `code_cache/com.android.opengl.shaders_cache` on startup and before each test run, while unique salt comments ensure 100% cache misses without causing SELinux directory mmap denials.
3. **Display Lifecycle Safety**: Thread B's standard context tears down using `destroy(terminateDisplay = false)` so it does not terminate the process-wide `EGL_DEFAULT_DISPLAY` singleton while Thread A's robust context is still active.
4. **Real-time Native Logcat Streaming**: A background thread monitors `logcat --pid=$myPid` and pipes native messages directly into the on-screen console (`[SYS] ...`), ensuring assertions printed by `libEGL` or `AdrenoGLES-0` are immediately visible.

---

## 5. Live Reproduction Logs: Pixel 5 (Adreno 620) vs. Pixel 8 Pro (Mali-G715)

The logs below were captured from live physical hardware running the identical test suite:

### A. Affected Device: Google Pixel 5 (Qualcomm Adreno 620)

**Driver & GPU Identification:**
```text
QUALCOMM build                   : 4783c89, I46ff5fc46f
Build Date                       : 11/30/20
OpenGL ES Shader Compiler Version: EV031.31.04.01
Build Config                     : S P 10.0.4 AArch64
Driver Path                      : /vendor/lib64/egl/libGLESv2_adreno.so
GPU Initialized: Adreno (TM) 620 (OpenGL ES 3.2 V@0490.0 (GIT@4783c89, I46ff5fc46f, 1606807783) (Date:11/30/20)) | Vendor: Qualcomm
```

**Cross-Context Poisoning Test Execution:**
```text
>>> STARTING: 4. Cross-Context Poisoning Test
>>> STEP 1: Thread A creates ROBUST context (0x30BF=1)...
Attempting robust EGL context creation: Primary [clientVersion=2, 0x30BF=1, 0x31BD=0x31BE] (attribs=[0x3098, 0x2, 0x30bf, 0x1, 0x31bd, 0x31be, 0x3038])...
Robust EGL context created successfully using Primary [clientVersion=2, 0x30BF=1, 0x31BD=0x31BE].
eglMakeCurrent succeeded.
[Thread A] Robust context created and made current.
Testing Context A Safe Shader (Shader running inside robust context)...
Context A Safe Shader LINK SUCCESS in 8 ms.
[Thread A] Safe shader link: SUCCESS
--------------------------------------------------
>>> STEP 2: Thread B creates STANDARD context...
  Thread B attribs: [0x3098=2, 0x3038=EGL_NONE] (ZERO robust attributes!)
Standard EGL context created successfully.
eglMakeCurrent succeeded.
[Thread B] Standard context created and made current.
>>> STEP 3: Thread B links Dynamic Indexing Shader (which PASSED in Button 1)...
Testing Dynamic Indexing Shader (Dynamic uniform array indexing via attribute)...
Calling glLinkProgram for Dynamic Indexing Shader...
Assertion failed: GVI && "cannot compute gv size for oob (no global info)"
Dynamic Indexing Shader LINK FAILED (status=0) in 9 ms. InfoLog: Assertion failed: GVI && "cannot compute gv size for oob (no global info)"
--- Dynamic Indexing Shader (Dynamic uniform array indexing via attribute) ---
  GL_RENDERER : Adreno (TM) 620
  GL_VERSION  : OpenGL ES 3.2 V@0490.0 (GIT@4783c89, I46ff5fc46f, 1606807783) (Date:11/30/20)
  GL_VENDOR   : Qualcomm
  VS Compile  : SUCCESS 
  FS Compile  : SUCCESS 
  Link Status : 0 (FAILED (GL_FALSE))
  Link Time   : 9 ms
  Link InfoLog: Assertion failed: GVI && "cannot compute gv size for oob (no global info)"
  Outcome     : FAILED

[CRITICAL: CROSS-CONTEXT DRIVER BLEEDING CONFIRMED!]
Context B (STANDARD) FAILED to link Dynamic Indexing Shader!
  Context B Attribs: [0x3098, 2, 0x3038] (NO ROBUST ACCESS)
  Assertion Error  : Assertion failed: GVI && "cannot compute gv size for oob (no global info)"
  Explanation: Robust access from Thread A permanently poisoned the process-wide Qualcomm compiler backend (/vendor/lib64/egl/libGLESv2_adreno.so / libllvm-glnext.so)!
```

---

### B. Control Device: Google Pixel 8 Pro (ARM Mali-G715 MC7)

**Driver & GPU Identification:**
```text
GPU Initialized: Mali-G715 MC7 (OpenGL ES 3.2 v1.r56p0-18eac0.285f3c61d48c74d025f038abebe42a6a) | Vendor: ARM
Driver Path: /vendor/lib64/egl/libGLES_mali.so
```

**Button 3: Direct Driver Bug Test (Robust Context + Dynamic Indexing):**
```text
>>> STARTING: 3. Direct Driver Bug Test (Robust Context + Dynamic Indexing)
Creating ROBUST EGL context (0x30BF=1, 0x31BD=0x31BE)...
Robust EGL context created successfully.
Testing Dynamic Indexing Shader under Robust Context...
--- Dynamic Indexing Shader (Dynamic uniform array indexing via attribute) ---
  GL_RENDERER : Mali-G715 MC7
  GL_VERSION  : OpenGL ES 3.2 v1.r56p0-18eac0.285f3c61d48c74d025f038abebe42a6a
  GL_VENDOR   : ARM
  VS Compile  : SUCCESS 
  FS Compile  : SUCCESS 
  Link Status : 1 (SUCCESS (GL_TRUE))
  Link Time   : 1 ms
  Link InfoLog: <empty>
  Outcome     : PASSED

RESULT: Dynamic Indexing Shader linked successfully (GL_TRUE).
  NOTE: On standard-compliant GPUs (e.g. ARM Mali-G715), dynamic uniform array indexing under robust access is fully supported by the spec.
```

**Button 4: Cross-Context Poisoning Test:**
```text
>>> STARTING: 4. Cross-Context Poisoning Test
>>> STEP 1: Thread A creates ROBUST context (0x30BF=1)...
Attempting robust EGL context creation: Primary [clientVersion=2, 0x30BF=1, 0x31BD=0x31BE] (attribs=[0x3098, 0x2, 0x30bf, 0x1, 0x31bd, 0x31be, 0x3038])...
Robust EGL context created successfully using Primary [clientVersion=2, 0x30BF=1, 0x31BD=0x31BE].
eglMakeCurrent succeeded.
[Thread A] Robust context created and made current.
Testing Context A Safe Shader (Shader running inside robust context)...
Context A Safe Shader LINK SUCCESS in 1 ms.
[Thread A] Safe shader link: SUCCESS
--------------------------------------------------
>>> STEP 2: Thread B creates STANDARD context...
  Thread B attribs: [0x3098=2, 0x3038=EGL_NONE] (ZERO robust attributes!)
Standard EGL context created successfully.
eglMakeCurrent succeeded.
[Thread B] Standard context created and made current.
>>> STEP 3: Thread B links Dynamic Indexing Shader (which PASSED in Button 1)...
Testing Dynamic Indexing Shader (Dynamic uniform array indexing via attribute)...
Calling glLinkProgram for Dynamic Indexing Shader...
Dynamic Indexing Shader LINK SUCCESS in 1 ms.
--- Dynamic Indexing Shader (Dynamic uniform array indexing via attribute) ---
  GL_RENDERER : Mali-G715 MC7
  GL_VERSION  : OpenGL ES 3.2 v1.r56p0-18eac0.285f3c61d48c74d025f038abebe42a6a
  GL_VENDOR   : ARM
  VS Compile  : SUCCESS 
  FS Compile  : SUCCESS 
  Link Status : 1 (SUCCESS (GL_TRUE))
  Link Time   : 1 ms
  Link InfoLog: <empty>
  Outcome     : PASSED

[NO POISONING DETECTED]: Dynamic Indexing Shader linked successfully on standard context.
```

---

## 6. Building and Running

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
