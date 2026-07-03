# TrueMiner Android GPU Edition

BTC/BCH educational solo miner for Android with CPU, GPU, and CPU+GPU modes.

## What was rebuilt

- Redesigned Android layout with separate cards for wallets, pools, performance, Telegram, status, and shares.
- Added **Save Settings** button using `SharedPreferences`.
- Added mining mode selector:
  - CPU only
  - GPU only
  - CPU + GPU
- Added GPU backend selector:
  - Auto
  - Vulkan
  - OpenCL
- Added native C++ JNI module under `app/src/main/cpp`.
- Added Vulkan compute shader under `app/src/main/shaders/sha256_miner.comp`.
- Added OpenCL dynamic loader for Android vendor `libOpenCL.so`.
- Fixed CPU miner threading: each worker now pulls unique nonce batches from a shared nonce cursor.
- Fixed SHA-256 thread safety: each hash now uses its own digest instance instead of a shared `MessageDigest`.
- Fixed Stratum extranonce handling and share submission byte order.
- Added real Telegram send support through the Bot API.
- Added live hashrate callback every few seconds.

## Build requirements

Open the project in Android Studio and make sure these SDK components are installed:

- Android SDK Platform 34
- Android SDK Build Tools
- Android NDK
- CMake 3.22+
- Android Gradle Plugin 8.2+

The Vulkan shader is compiled by the Android Gradle shader pipeline from:

```text
app/src/main/shaders/sha256_miner.comp
```

The native miner then loads the compiled SPIR-V asset at runtime. If the shader asset is missing on your setup, Auto mode will try OpenCL, then native CPU fallback.

## Android GPU notes

- **Vulkan** is the best Android GPU path because Android officially supports Vulkan.
- **OpenCL** is not exposed by every Android ROM. This app dynamically loads vendor `libOpenCL.so` when available, so it can work on devices that ship OpenCL without bundling illegal vendor libraries.
- Auto mode tries Vulkan first, OpenCL second, and native CPU fallback last.

## Use

1. Enter your BTC address and pool.
2. Optional: enable BCH dual pool.
3. Choose CPU, GPU, or CPU + GPU.
4. Choose Auto, Vulkan, or OpenCL backend.
5. Tap **Save Settings**.
6. Tap **Start**.

## Disclaimer

Solo mining BTC/BCH on a phone is mostly educational and very unlikely to find a block. Keep mining visible to the user and do not run it secretly or without consent.
