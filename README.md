# Argon x LiquidBounce GUI Integration Guide

This document is written for an AI coding assistant to provide a comprehensive roadmap for transferring the **LiquidBounce** GUI onto the **Argon** client.

**Goal:** Use the LiquidBounce source to completely replace the visual GUI of Argon so that it looks and behaves *exactly* the same as LiquidBounce, while keeping Argon's underlying modules and settings intact. You will *not* be building a theme; you are ripping out Argon's native GUI and implanting LiquidBounce's web-based JCEF (Chromium) GUI system.

---

## Architectural Breakdown

### 1. Argon's Backend (Keep Intact)
Argon relies on a standard immediate-mode module/setting architecture:
- **Modules**: Inherit from `dev.lvstrng.argon.module.Module`. They have a Name, Description, and `Category`.
- **Settings**: Defined in `dev.lvstrng.argon.module.setting.*`. Core types include `BooleanSetting`, `NumberSetting`, `KeybindSetting`, and `ModeSetting`.
- **State**: Modules have a toggled state and settings have a `.getValue()` and `.setValue()` API.

### 2. Argon's Frontend (To Be Replaced)
Argon currently uses a native Java/OpenGL immediate-mode rendering pipeline for its GUI.
- **Entrypoints**: `dev.lvstrng.argon.gui.ClickGui` and `dev.lvstrng.argon.gui.Window`.
- **Rendering**: Done natively via Minecraft's `DrawContext`.
- **Action**: This entire `dev.lvstrng.argon.gui` package will be rendered obsolete and replaced by the LiquidBounce CEF wrapper.

### 3. LiquidBounce's Frontend (The Target)
LiquidBounce (NextGen) uses a completely different paradigm for its GUI: **Web Technologies**.
- **The UI**: The GUI is a fully standalone **Svelte** web application located in `LiquidBounce-e77a6699123fad2e2d50748c0c7f764932bb0b13/src-theme`.
- **The Wrapper**: The Svelte app is rendered inside Minecraft using Chromium Embedded Framework (CEF/JCEF).
- **The Screen**: The integration is handled via `net.ccbluex.liquidbounce.integration.screen.ScreenManager` and `CustomSharedMinecraftScreen`.

### 4. LiquidBounce's Interop (The Bridge)
Because the UI is a web app and the cheat logic is in Java/Kotlin, LiquidBounce uses an internal server to communicate.
- **Location**: `net.ccbluex.liquidbounce.integration.interop.ClientInteropServer`
- **Functionality**: It hosts a local REST/WebSocket API that the Svelte frontend queries to get the list of modules, toggle states, and settings. 

---

## Step-by-Step Implementation Plan

To successfully merge LiquidBounce's GUI onto Argon, follow these phases:

### Phase 1: Engine & Dependency Setup
You must bring JCEF (Chromium Embedded Framework) into Argon's build environment.
1. Extract LiquidBounce's Gradle dependencies relating to CEF/JCEF.
2. Port the `net.ccbluex.liquidbounce.integration.screen` package over to Argon. You will need to convert these Kotlin classes to Java or enable Kotlin support in Argon's `build.gradle`.

### Phase 2: Interop Server & Bridge Adaptation
The Svelte frontend expects an API to provide module/setting data. You need to provide that API using Argon's backend.
1. Port LiquidBounce's `ClientInteropServer` and its `protocol` package into Argon.
2. **Crucial Step**: Modify the REST endpoints in the Interop server. Instead of fetching LiquidBounce modules (`ClientModule`), rewrite the endpoints to iterate over Argon's `ModuleManager` and serialize Argon's `dev.lvstrng.argon.module.setting.*` instances into the JSON format expected by the Svelte frontend.
3. Map Svelte UI setting changes (e.g., a POST request to update a slider) to Argon's `NumberSetting.setValue()`.

### Phase 3: GUI Replacement
1. Delete or deprecate Argon's `ClickGui.java`.
2. Hook Argon's "Right Shift" (or generic ClickGUI keybind) to open the LiquidBounce `CustomSharedMinecraftScreen` wrapper instead.
3. Ensure the CEF browser points to the internal web server hosted by `ClientInteropServer`.

### Phase 4: Build System & Asset Integration
1. The Svelte app (`src-theme/`) must be compiled (`npm run build`).
2. Integrate the compiled Svelte output (`dist/`) into Argon's `src/main/resources/assets/argon/theme/` (or similar).
3. Ensure Argon's embedded server correctly serves these static HTML/JS/CSS files to the CEF browser instance.

## Summary for the AI
Do not attempt to recreate LiquidBounce's visuals natively using `DrawContext`. The exact LiquidBounce GUI relies on HTML/CSS/JS. Your objective is to embed a Chromium window into Argon, host the LiquidBounce Svelte app locally, and rewrite the Java-to-JS bridge to feed Argon's modules and settings into LiquidBounce's API schema.
