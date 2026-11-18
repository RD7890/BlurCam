# BlurCam

Record sharp, add cinematic depth of field afterwards. Fully offline, no frame-time budget.

**Pipeline:** CameraX capture → per-frame person matte (ML Kit Selfie Segmentation) → GPU disc-kernel bokeh
(GLSL ES 3.0, background-only weighting = no halos, optional highlight bloom) → H.264 encode → original audio muxed back.

## MVP scope (honest)
- Subject detection is **person-only**. With no person in frame, BlurCam falls back to a soft centre-focus ellipse.
- Blur strength is set by aperture (f/1.4 – f/16); there is no depth map yet, so blur is uniform behind the subject.
- Rendering runs while the app is open (screen is kept awake). Clips are capped at 30 s.
- Next steps: monocular video-depth model (Video Depth Anything) for true depth falloff, rack focus,
  foreground service / WorkManager for background renders.

## Build
```
./gradlew :app:assembleRelease          # needs JDK 17 + Android SDK
```
Release signing reads `local.properties` (see `local.properties.example`); without it the build falls back to debug signing.
CI (`.github/workflows/build.yml`) creates that file itself, bumps `versionCode/versionName`, builds ABI-split APKs and
publishes them as a GitHub Release on every push to `main` or `release/**`.

## Signing key
`signing/signing-key.jks` is committed on purpose (the workflow expects it). Alias `blurcam`, store/key password `blurcam123`.
If the repo is public, anyone can sign APKs with this key. For a public repo, make it private, or swap to the
`SIGNING_KEYSTORE_B64` secret and delete the committed file. **Never regenerate it** after users install your APK,
or updates will stop working.

## Credits
Icons: [Phosphor Icons](https://phosphoricons.com) (MIT). Fonts: Space Grotesk and JetBrains Mono (SIL OFL 1.1).

## Troubleshooting
- **ML Kit:** its auto-init content provider is removed in the manifest and ML Kit is initialised in `BlurCamApp`
  (`MlKitGate`). If ML Kit is ever unavailable the app still starts and Studio shows "PERSON DETECTION UNAVAILABLE"
  (centre-focus fallback). The CI step "Verify ML Kit is packaged in the APK" prints the resolved ML Kit artifacts
  and checks the dex for the ML Kit classes on every build.
