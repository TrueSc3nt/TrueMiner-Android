package com.trueminer.mining

enum class MiningMode(val label: String) {
    CPU("CPU only"),
    GPU("GPU only"),
    BOTH("CPU + GPU")
}

enum class GpuBackend(val label: String) {
    AUTO("Auto"),
    VULKAN("Vulkan"),
    OPENCL("OpenCL")
}
