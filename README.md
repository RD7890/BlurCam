# BlurCam

Record sharp, add real cinematic depth of field afterwards. Fully offline.

## How it works
```
decode (MediaCodec, YUV, no seeking)
  └─ every N frames: Depth Anything V2 Small (ONNX Runtime) → stable 0..1 disparity
       └─ focus: Auto (nearest substantial object near centre) or Tap
            └─ region-grow across smooth depth → whole subject (head + body) = one in-focus plane
GPU, every frame (GLSL ES 3.0, see Shaders.kt)
  1. ingest  : YUV/rotation → upright RGB (+ mips)
  2. coc     : interpolate depth between key-frames, joint-bilateral upsample (edges snap to the image) → blur radius
  3. gather  : disc kernel with scatter-style weights → no halos, subject never bleeds into the background
→ H.264 encoder surface → mux + original audio
```
Decode+depth and GL+encode run on separate threads, so total time ≈ max(depth, render), not the sum.

| Quality | Output | Depth input | Depth every |
|---|---|---|---|
| Fast | 720p | 196 px short side | 8 frames |
| Best | 1080p | 266 px short side | 4 frames |

## Build
The ~100 MB depth model is **not** in git. CI downloads it; locally run once:
```
./scripts/fetch_model.sh
./gradlew :app:assembleRelease          # JDK 17 + Android SDK
```
Release signing reads `local.properties` (see `local.properties.example`); without it the build falls back to debug signing.
CI (`.github/workflows/build.yml`) bumps `versionCode/versionName`, builds ABI-split APKs and publishes a GitHub Release on every
push to `main` or `release/**`. Each APK is ~110 MB because it embeds the model.

## Offline test harness
`tools/harness/harness.py` runs the **shipped** shaders (extracted from `Shaders.kt`) through Mesa/EGL with the real
depth model on a still image. Useful to tune `Shaders.kt` / focus logic without a phone:
`pip install onnxruntime moderngl opencv-python-headless scikit-image numpy` then `python tools/harness/harness.py model.onnx`.

## Known limits
- Monocular depth is relative. Mirrors/glass, very thin objects and night scenes with no texture can fool it. Tap the subject to override Auto.
- Fast subject motion between depth key-frames is interpolated; use **Best** for sports-like motion.
- Rendering runs while the app is open (screen kept awake). Clips are capped at 30 s.

## Signing key
`signing/signing-key.jks` is committed on purpose (the workflow expects it). Alias `blurcam`, store/key password `blurcam123`.
For a public repo anyone could sign APKs with it: make the repo private or use a `SIGNING_KEYSTORE_B64` secret.
**Never regenerate it** after users install your APK.

## Credits / licences
Depth Anything V2 Small (Apache-2.0), ONNX export by fabio-sim/Depth-Anything-ONNX. ONNX Runtime (MIT).
Icons: Phosphor (MIT). Fonts: Space Grotesk, JetBrains Mono (SIL OFL 1.1).
