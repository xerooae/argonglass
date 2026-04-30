# Argon to LiquidBounce GUI Rework Plan

## 1. Fix Module Interactivity & Expanding
**Problem**: You noticed that clicking/expanding modules to view their settings doesn't work.
**Cause**: The Svelte GUI makes a request to `/api/v1/client/modules/settings?name=ModuleName`. However, `ArgonInteropServer` does not URL-decode the query parameter. If Svelte requests `Auto%20WTap`, Argon searches for literally `"Auto%20WTap"` instead of `"Auto WTap"`, returning `null`. Additionally, Svelte expects a specific JSON format for the settings.
**Fix**:
- Add `URLDecoder.decode(query.substring(5), StandardCharsets.UTF_8)` to `handleSettings`.
- Ensure `settingToJson` properly maps Argon's settings to LiquidBounce's expected types (`Boolean`, `Float`, `Integer`, `Text`, `List`).

## 2. Category Harmonization
**Problem**: The user requested removing current GUI modules and remaking categories to match Argon.
**Clarification**: The Svelte GUI is actually *dynamically* rendering the modules that Argon sends it. There are no "hardcoded" modules in Svelte! The reason they look weird is because Argon's `Category` enum names (e.g., `COMBAT`) don't perfectly match LiquidBounce's expected capitalization (e.g., `Combat`), breaking the SVG icons and layout.
**Fix**:
- Modify `ArgonInteropServer`'s `categoryTag` to correctly title-case the categories (e.g., `COMBAT` -> `Combat`).
- Check if Svelte's CSS has missing icons and map Argon's categories to the closest LiquidBounce equivalent.

## 3. Settings Mapping (Reworking Modules to fit LB)
**Problem**: Argon's settings need to be displayed natively in LiquidBounce's UI.
**Fix**:
- Map Argon's `BooleanSetting` to LiquidBounce's `"Boolean"` setting type.
- Map Argon's `FloatSetting` and `DoubleSetting` to `"Float"`.
- Map Argon's `ModeSetting`/`EnumSetting` to `"List"`.
- Ensure the `handleSettingsUpdate` endpoint in `ArgonInteropServer` properly updates the Argon `Setting` objects when you interact with the UI.

Let me know if you approve this plan, and I will begin implementing the URL decoding and settings mapping so you can interact with the modules!
