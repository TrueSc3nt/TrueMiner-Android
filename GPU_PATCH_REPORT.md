# GPU Patch Report

## Added

- CPU / GPU / CPU+GPU selector in the redesigned main screen.
- GPU backend selector: Auto, Vulkan, OpenCL.
- Save Settings button and persistent settings storage.
- Native JNI module: `app/src/main/cpp/native_miner.cpp`.
- Vulkan compute shader: `app/src/main/shaders/sha256_miner.comp`.
- OpenCL dynamic loader for Android vendor `libOpenCL.so`.
- GPU worker: `GPUMinerWorker.kt`.
- Native bridge classes: `NativeMiner.kt`, `NativeScanResult.kt`, `MiningMode.kt`.

## Fixed

- Existing GPU toggle did nothing; it is now wired into `MinerService`.
- CPU workers previously fought over a single queue job; workers now pull unique nonce batches using a shared atomic cursor.
- SHA-256 hashing no longer shares one `MessageDigest` across threads.
- Hashrate now updates live instead of waiting a full minute.
- Stratum extranonce2 parsing now handles Gson `Double`/`Number` values safely.
- Basic Stratum coinbase/merkle/header build path was rebuilt.
- Settings are saved before starting and can be manually saved with the new button.
- Telegram share alert sending now uses OkHttp and the Telegram Bot API.

## Important build note

I could not run a full Android Gradle build in this environment because the Gradle wrapper tried to download Gradle from `services.gradle.org`, and outbound internet is unavailable here. XML/resources were checked locally, and the project has been patched for Android Studio/NDK builds.

## GPU behavior

- Auto mode tries Vulkan first, then OpenCL, then native CPU fallback.
- Vulkan requires the Android NDK shader pipeline to compile `sha256_miner.comp` into SPIR-V assets.
- OpenCL works only on Android devices/ROMs that expose vendor `libOpenCL.so`.
