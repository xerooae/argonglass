# Argon x LiquidBounce GUI Port Progress

## Current State
The full infrastructure is in place. Three bugs have been identified and fixed (see below).
The next step is to launch the game and confirm the GUI renders.

---

## ✅ Fixed Issues

### Fix 1 — IPv4/IPv6 Loopback Mismatch (Root Cause of "Connection refused")
**File:** `ArgonInteropServer.java`

Windows resolves the hostname `"localhost"` to `::1` (IPv6) in many JDK configurations.
The `ServerSocket` was only bound to `127.0.0.1` (IPv4), so MCEF/Chromium, which Chromium resolves
`localhost` → `::1`, was hitting a refused connection.

**Changes:**
- `ServerSocket` now binds to `0.0.0.0` (all interfaces) so both `127.0.0.1` and `::1` are served.
- `getUrl()` now returns `http://127.0.0.1:<PORT>` (numeric IPv4) so MCEF never needs DNS resolution.
- Executor thread pool is now initialised eagerly in `start()`, not lazily in the accept loop.

### Fix 2 — `glBegin`/`glEnd` Removed in OpenGL Core Profile (Browser Never Visible)
**File:** `ArgonBrowserScreen.java`

Minecraft 1.21.x uses an OpenGL Core Profile context, which **removes** the legacy immediate-mode
API (`glBegin`, `glEnd`, `glTexCoord2f`, `glVertex2f`). These calls silently do nothing (or crash
in some drivers), so the browser texture was never rendered even when the browser successfully loaded.

**Changes:**
- Added `initQuad()` which builds a proper VAO + VBO fullscreen quad and compiles a minimal
  `#version 150 core` GLSL blit program (vertex + fragment shaders inline).
- `render()` now uses `glUseProgram` + `glBindVertexArray` + `glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)`.
- GL state (program, VAO, depth test) is saved and restored correctly after each blit.
- VAO, VBO, and shader program are deleted properly in `close()`.

### Fix 3 — Wrong Window Size Passed to `browser.resize()`
**File:** `ArgonBrowserScreen.java`

`getWindow().getWidth()` returns the OS window width in scaled GUI units.
`browser.resize()` expects **framebuffer** pixel dimensions.
Using the wrong values caused the Chromium viewport to be scaled incorrectly.

**Change:** `init()` now calls `getWindow().getFramebufferWidth/Height()`.

### Fix 4 — MCEF Message Loop Not Pumped
**File:** `ArgonBrowserScreen.java`

MCEF requires `MCEF.INSTANCE.update()` to be called on the render thread each frame so the
browser process can decode network responses, execute JS, and upload texture data.
Without this call the texture ID is always 0 and the browser never makes visible progress.

**Change:** `render()` now calls `MCEF.INSTANCE.update()` before reading `getTextureId()`.

---

## What Is Working
1. **Background MCEF Initialization:** `MCEFInitializer.java` downloads & initializes Chromium binaries.
2. **Interop Server:** Binds on `0.0.0.0`, URL is `http://127.0.0.1:<PORT>`.
3. **Screen Creation:** `ArgonBrowserScreen` is opened when the GUI key is pressed.
4. **Rendering Pipeline:** Core-Profile compliant VAO/VBO blit, no legacy `glBegin`.

---

## Next Steps
1. **Run the game** (`./gradlew runClient`) and open the GUI (default key).
2. Watch logs for:
   - `[ArgonInterop] Server bound to: 0.0.0.0:<PORT>`
   - `[ArgonInterop] Accepted connection from: /127.0.0.1:...`  ← confirms MCEF connects
   - `[ArgonBrowser] Quad VAO/VBO + blit shader initialized.`
   - `[ArgonBrowser] First texture ID received: <N>`  ← confirms rendering works
3. If you see "Accepted connection" but no texture, the Svelte assets may be missing from
   `src/main/resources/assets/argon/theme/liquidbounce/`. Verify `index.html` is there.
4. If there is no "Accepted connection" at all, enable Chromium net-log via `MCEFSettings` to
   diagnose what URL Chromium is actually trying to reach.
