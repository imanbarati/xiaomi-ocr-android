# Xiaomi OCR Android

An experimental, fully on-device Android frontend for [Xiaomi-OCR-0](https://github.com/imanbarati/Xiaomi-OCR-0).

## Target device

Designed for Samsung Galaxy A53 5G (ARM64 / Exynos 1280), but **not yet performance-validated on a physical A53**. It uses llama.cpp's Android ARM64 `llama-server` build and the community Q4_K_M GGUF conversion.

- Language model: Q4_K_M (about 529 MB)
- Vision projector: F16 mmproj (about 207 MB)
- Both files are downloaded/imported by the user; they are not bundled in the APK.
- Inference is local on `127.0.0.1`; no OCR image is sent to a cloud service.
- Whole-page OCR first. Region parsing / PaddleX is not included.

## Important compatibility note

The upstream project documents Qwen3.5 GGUF support through llama.cpp. The community conversion repository reports Q4_K_M and F16 mmproj files. This Android app builds llama.cpp's server for `arm64-v8a` in CI. The build is experimental: confirm that the pinned llama.cpp revision still builds the `llama-server` target for Android and that the generated executable runs on the target phone before treating an APK as production-ready.

A53 expectations: Q4_K_M + mmproj occupy roughly 736 MB on disk, but runtime memory is higher due to model buffers, image processing, context/KV cache and Android overhead. Start with context 2048 and one image at a time. Close other apps. Performance may be slow; use reduced image dimensions if memory pressure occurs.

## Build

1. Push this project to GitHub.
2. GitHub Actions builds the ARM64 llama.cpp server, packages it into the Android app, and creates a debug APK artifact.
3. Download `app-debug.apk` from the workflow artifact and install it on the A53.
4. Download the two GGUF files from the model links below using a browser, then select them in the app with the file picker. Keep at least 1 GB free for the model pair plus working space.
5. Pick an image and run OCR.

The workflow uses Android SDK/NDK and Gradle supplied by GitHub Actions. The APK is a debug build and is not Play Store signed.

## Model sources

- Model: `https://huggingface.co/prithivMLmods/Xiaomi-OCR-0-GGUF/resolve/main/Xiaomi-OCR-0.Q4_K_M.gguf`
- Vision projector: `https://huggingface.co/prithivMLmods/Xiaomi-OCR-0-GGUF/resolve/main/Xiaomi-OCR-0.mmproj-bf16.gguf`
- Alternative reproducible conversion and checksums: https://github.com/xzl01/Xiaomi-OCR-0-GGUF

Check the model card and upstream license before redistributing weights. This repository does not include model weights.

## Current limitations

- First version focuses on page-to-Markdown OCR.
- Model download requires a reliable connection and several hundred MB of free storage.
- Android background execution can be interrupted by battery optimization; keep the app foregrounded during inference.
- The app reports runtime/build errors rather than silently falling back to cloud OCR.
- Physical-device smoke tests and accuracy comparisons are still required.
