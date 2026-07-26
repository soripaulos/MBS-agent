# Omnitrix asset swap guide

The app ships with **original placeholder dial art** (simple geometric hourglass dials).
To use authentic artwork, replace the files below **keeping the exact file names** — no
code changes needed. All are standard Android vector drawables; if your source is an SVG,
import it via Android Studio (`New → Vector Asset → Local file`) or any svg→vector-xml
converter, then paste the result into the file.

## Files to replace

All under `app/src/main/res/`:

| File | Used for | Specs |
|---|---|---|
| `drawable/omnitrix_original.xml` | Original-series dial (selector + launcher icon foreground) | 108×108 viewport; keep content in the middle ~66% (adaptive-icon safe zone) |
| `drawable/omnitrix_alien_force.xml` | Alien Force dial | same |
| `drawable/omnitrix_ultimatrix.xml` | Ultimatrix dial | same |
| `drawable/omnitrix_omniverse.xml` | Omniverse dial (default identity) | same |
| `drawable/omnitrix_splash.xml` | Splash artwork (central dial + satellites) | 108×108 viewport. Keep a `<group android:name="constellation" android:pivotX="54" android:pivotY="54">` around whatever should spin — the splash animation rotates that group |

## What consumes them

- **Assistant selector dial** (`OmnitrixSelector.kt`) — shows the variant chosen in
  Settings → Preferences → UI → Omnitrix.
- **Launcher icons** (`mipmap-anydpi-v26/ic_launcher_omni_*.xml`) — each adaptive icon
  uses the matching dial as its foreground over the `omnitrix_icon_bg` color
  (`values/colors.xml`, default near-black green `#0B0F0C`). Change that color to match
  your art.
- **Animated splash** (`drawable/omnitrix_splash_animated.xml` + `animator/omnitrix_spin.xml`,
  Android 12+) — spins the `constellation` group of `omnitrix_splash.xml` over the
  `windowSplashScreenBackground` color set in `values-v31/themes.xml`.

## Raster art instead of vectors?

If you only have PNGs: put density buckets under `drawable-xxhdpi/` etc. with the same
base names (`omnitrix_original.png`, …) and delete the correspondingly-named XML files.
Everything referencing `@drawable/omnitrix_*` keeps working. For the splash, a static PNG
also works but loses the spin (the AVD needs a vector group to rotate) — in that case
point `values-v31/themes.xml` `windowSplashScreenAnimatedIcon` at the PNG directly.

## Legal note

The Omnitrix designs are Cartoon Network / Ben 10 IP. The bundled placeholders are
deliberately generic; source your replacement artwork appropriately for personal use.
