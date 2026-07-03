#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <vulkan/vulkan.h>
#include <dlfcn.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

#define LOG_TAG "TrueMinerNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct ScanResult {
    bool found = false;
    uint32_t nonce = 0;
    uint8_t hash[32]{};
    uint64_t hashesDone = 0;
    std::string backendUsed;
    std::string error;
};

static inline uint32_t rotr(uint32_t x, uint32_t n) { return (x >> n) | (x << (32u - n)); }
static inline uint32_t read_be32(const uint8_t* p) {
    return (uint32_t(p[0]) << 24u) | (uint32_t(p[1]) << 16u) | (uint32_t(p[2]) << 8u) | uint32_t(p[3]);
}
static inline void write_be32(uint8_t* p, uint32_t v) {
    p[0] = uint8_t((v >> 24u) & 0xffu);
    p[1] = uint8_t((v >> 16u) & 0xffu);
    p[2] = uint8_t((v >> 8u) & 0xffu);
    p[3] = uint8_t(v & 0xffu);
}

const uint32_t SHA256_K[64] = {
    0x428a2f98u,0x71374491u,0xb5c0fbcfu,0xe9b5dba5u,0x3956c25bu,0x59f111f1u,0x923f82a4u,0xab1c5ed5u,
    0xd807aa98u,0x12835b01u,0x243185beu,0x550c7dc3u,0x72be5d74u,0x80deb1feu,0x9bdc06a7u,0xc19bf174u,
    0xe49b69c1u,0xefbe4786u,0x0fc19dc6u,0x240ca1ccu,0x2de92c6fu,0x4a7484aau,0x5cb0a9dcu,0x76f988dau,
    0x983e5152u,0xa831c66du,0xb00327c8u,0xbf597fc7u,0xc6e00bf3u,0xd5a79147u,0x06ca6351u,0x14292967u,
    0x27b70a85u,0x2e1b2138u,0x4d2c6dfcu,0x53380d13u,0x650a7354u,0x766a0abbu,0x81c2c92eu,0x92722c85u,
    0xa2bfe8a1u,0xa81a664bu,0xc24b8b70u,0xc76c51a3u,0xd192e819u,0xd6990624u,0xf40e3585u,0x106aa070u,
    0x19a4c116u,0x1e376c08u,0x2748774cu,0x34b0bcb5u,0x391c0cb3u,0x4ed8aa4au,0x5b9cca4fu,0x682e6ff3u,
    0x748f82eeu,0x78a5636fu,0x84c87814u,0x8cc70208u,0x90befffau,0xa4506cebu,0xbef9a3f7u,0xc67178f2u
};

void sha256_compress(uint32_t st[8], const uint8_t block[64]) {
    uint32_t w[64];
    for (int i = 0; i < 16; ++i) w[i] = read_be32(block + i * 4);
    for (int i = 16; i < 64; ++i) {
        uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    uint32_t a = st[0], b = st[1], c = st[2], d = st[3], e = st[4], f = st[5], g = st[6], h = st[7];
    for (int i = 0; i < 64; ++i) {
        uint32_t S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
        uint32_t ch = (e & f) ^ (~e & g);
        uint32_t temp1 = h + S1 + ch + SHA256_K[i] + w[i];
        uint32_t S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t temp2 = S0 + maj;
        h = g; g = f; f = e; e = d + temp1;
        d = c; c = b; b = a; a = temp1 + temp2;
    }
    st[0] += a; st[1] += b; st[2] += c; st[3] += d;
    st[4] += e; st[5] += f; st[6] += g; st[7] += h;
}

void sha256_bytes(const uint8_t* data, size_t len, uint8_t out[32]) {
    uint32_t st[8] = {0x6a09e667u,0xbb67ae85u,0x3c6ef372u,0xa54ff53au,0x510e527fu,0x9b05688cu,0x1f83d9abu,0x5be0cd19u};
    std::vector<uint8_t> msg(data, data + len);
    uint64_t bitLen = uint64_t(len) * 8u;
    msg.push_back(0x80u);
    while ((msg.size() % 64u) != 56u) msg.push_back(0u);
    for (int i = 7; i >= 0; --i) msg.push_back(uint8_t((bitLen >> (i * 8)) & 0xffu));
    for (size_t off = 0; off < msg.size(); off += 64) sha256_compress(st, msg.data() + off);
    for (int i = 0; i < 8; ++i) write_be32(out + i * 4, st[i]);
}

void double_sha256_header(const uint8_t header[80], uint32_t nonce, uint8_t out[32]) {
    uint8_t block[80];
    std::memcpy(block, header, 80);
    block[76] = uint8_t(nonce & 0xffu);
    block[77] = uint8_t((nonce >> 8u) & 0xffu);
    block[78] = uint8_t((nonce >> 16u) & 0xffu);
    block[79] = uint8_t((nonce >> 24u) & 0xffu);
    uint8_t first[32];
    sha256_bytes(block, 80, first);
    sha256_bytes(first, 32, out);
}

bool meets_target(const uint8_t hash[32], const uint8_t target[32]) {
    for (int i = 0; i < 32; ++i) {
        if (hash[i] < target[i]) return true;
        if (hash[i] > target[i]) return false;
    }
    return true;
}

void prepare_words(const uint8_t header[80], const uint8_t target[32], uint32_t input[30]) {
    for (int i = 0; i < 20; ++i) input[i] = read_be32(header + i * 4);
    for (int i = 0; i < 8; ++i) input[20 + i] = read_be32(target + i * 4);
}

ScanResult scan_cpu(const uint8_t header[80], const uint8_t target[32], uint64_t startNonce, uint32_t count) {
    ScanResult r;
    r.backendUsed = "Native CPU fallback";
    uint8_t hash[32];
    for (uint32_t i = 0; i < count; ++i) {
        uint32_t nonce = uint32_t(startNonce + i);
        double_sha256_header(header, nonce, hash);
        ++r.hashesDone;
        if (meets_target(hash, target)) {
            r.found = true;
            r.nonce = nonce;
            std::memcpy(r.hash, hash, 32);
            return r;
        }
    }
    return r;
}

std::vector<uint8_t> load_asset(AAssetManager* mgr, const char* name) {
    if (!mgr) return {};
    AAsset* asset = AAssetManager_open(mgr, name, AASSET_MODE_BUFFER);
    if (!asset) return {};
    off_t len = AAsset_getLength(asset);
    std::vector<uint8_t> bytes(static_cast<size_t>(len));
    int read = AAsset_read(asset, bytes.data(), len);
    AAsset_close(asset);
    if (read != len) return {};
    return bytes;
}

std::vector<uint8_t> load_shader(AAssetManager* mgr) {
    const char* names[] = {
        "shaders/sha256_miner.comp.spv",
        "shaders/sha256_miner.spv",
        "sha256_miner.comp.spv",
        "sha256_miner.spv"
    };
    for (const char* n : names) {
        auto data = load_asset(mgr, n);
        if (!data.empty()) return data;
    }
    return {};
}

std::string vk_result_to_string(VkResult res) {
    std::ostringstream oss;
    oss << "VkResult " << int(res);
    return oss.str();
}

uint32_t find_memory_type(VkPhysicalDevice phys, uint32_t typeFilter, VkMemoryPropertyFlags props) {
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(phys, &memProps);
    for (uint32_t i = 0; i < memProps.memoryTypeCount; ++i) {
        if ((typeFilter & (1u << i)) && (memProps.memoryTypes[i].propertyFlags & props) == props) return i;
    }
    return UINT32_MAX;
}

bool create_host_buffer(VkDevice device, VkPhysicalDevice phys, VkDeviceSize size, VkBufferUsageFlags usage,
                        VkBuffer* buffer, VkDeviceMemory* memory, std::string& err) {
    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bi.size = size;
    bi.usage = usage;
    bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VkResult res = vkCreateBuffer(device, &bi, nullptr, buffer);
    if (res != VK_SUCCESS) { err = "vkCreateBuffer failed " + vk_result_to_string(res); return false; }
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(device, *buffer, &req);
    uint32_t type = find_memory_type(phys, req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (type == UINT32_MAX) { err = "No host-visible coherent Vulkan memory type"; return false; }
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = type;
    res = vkAllocateMemory(device, &ai, nullptr, memory);
    if (res != VK_SUCCESS) { err = "vkAllocateMemory failed " + vk_result_to_string(res); return false; }
    res = vkBindBufferMemory(device, *buffer, *memory, 0);
    if (res != VK_SUCCESS) { err = "vkBindBufferMemory failed " + vk_result_to_string(res); return false; }
    return true;
}

struct GpuInput {
    uint32_t header[20];
    uint32_t target[8];
    uint32_t startNonce;
    uint32_t count;
};
struct GpuOutput {
    uint32_t found;
    uint32_t nonce;
    uint32_t hash[8];
    uint32_t hashes;
};

ScanResult scan_vulkan(const uint8_t header[80], const uint8_t target[32], uint64_t startNonce, uint32_t count, AAssetManager* mgr) {
    ScanResult out;
    out.backendUsed = "Vulkan";
    auto shaderBytes = load_shader(mgr);
    if (shaderBytes.empty()) { out.error = "Vulkan shader asset missing; install NDK shader tools or use OpenCL/CPU"; return out; }
    if ((shaderBytes.size() % 4) != 0) { out.error = "Vulkan shader asset is not valid SPIR-V"; return out; }

    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice phys = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkBuffer inBuffer = VK_NULL_HANDLE, outBuffer = VK_NULL_HANDLE;
    VkDeviceMemory inMemory = VK_NULL_HANDLE, outMemory = VK_NULL_HANDLE;
    VkShaderModule shader = VK_NULL_HANDLE;
    VkDescriptorSetLayout layout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;

    auto cleanup = [&]() {
        if (device != VK_NULL_HANDLE) vkDeviceWaitIdle(device);
        if (commandPool) vkDestroyCommandPool(device, commandPool, nullptr);
        if (descriptorPool) vkDestroyDescriptorPool(device, descriptorPool, nullptr);
        if (pipeline) vkDestroyPipeline(device, pipeline, nullptr);
        if (pipelineLayout) vkDestroyPipelineLayout(device, pipelineLayout, nullptr);
        if (layout) vkDestroyDescriptorSetLayout(device, layout, nullptr);
        if (shader) vkDestroyShaderModule(device, shader, nullptr);
        if (inBuffer) vkDestroyBuffer(device, inBuffer, nullptr);
        if (outBuffer) vkDestroyBuffer(device, outBuffer, nullptr);
        if (inMemory) vkFreeMemory(device, inMemory, nullptr);
        if (outMemory) vkFreeMemory(device, outMemory, nullptr);
        if (device) vkDestroyDevice(device, nullptr);
        if (instance) vkDestroyInstance(instance, nullptr);
    };

    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "TrueMiner";
    app.applicationVersion = 1;
    app.pEngineName = "TrueMinerNative";
    app.engineVersion = 1;
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    VkResult res = vkCreateInstance(&ici, nullptr, &instance);
    if (res != VK_SUCCESS) { out.error = "vkCreateInstance failed " + vk_result_to_string(res); cleanup(); return out; }

    uint32_t devCount = 0;
    vkEnumeratePhysicalDevices(instance, &devCount, nullptr);
    if (devCount == 0) { out.error = "No Vulkan physical device"; cleanup(); return out; }
    std::vector<VkPhysicalDevice> devices(devCount);
    vkEnumeratePhysicalDevices(instance, &devCount, devices.data());

    uint32_t queueFamily = UINT32_MAX;
    for (auto d : devices) {
        uint32_t qCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(d, &qCount, nullptr);
        std::vector<VkQueueFamilyProperties> qprops(qCount);
        vkGetPhysicalDeviceQueueFamilyProperties(d, &qCount, qprops.data());
        for (uint32_t i = 0; i < qCount; ++i) {
            if (qprops[i].queueFlags & VK_QUEUE_COMPUTE_BIT) { phys = d; queueFamily = i; break; }
        }
        if (phys) break;
    }
    if (!phys) { out.error = "Vulkan device has no compute queue"; cleanup(); return out; }

    float priority = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = queueFamily;
    qci.queueCount = 1;
    qci.pQueuePriorities = &priority;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    res = vkCreateDevice(phys, &dci, nullptr, &device);
    if (res != VK_SUCCESS) { out.error = "vkCreateDevice failed " + vk_result_to_string(res); cleanup(); return out; }
    vkGetDeviceQueue(device, queueFamily, 0, &queue);

    std::string err;
    if (!create_host_buffer(device, phys, sizeof(GpuInput), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, &inBuffer, &inMemory, err)) { out.error = err; cleanup(); return out; }
    if (!create_host_buffer(device, phys, sizeof(GpuOutput), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, &outBuffer, &outMemory, err)) { out.error = err; cleanup(); return out; }

    GpuInput input{};
    uint32_t words[30]{};
    prepare_words(header, target, words);
    for (int i = 0; i < 20; ++i) input.header[i] = words[i];
    for (int i = 0; i < 8; ++i) input.target[i] = words[20 + i];
    input.startNonce = uint32_t(startNonce);
    input.count = count;
    GpuOutput output{};

    void* mapped = nullptr;
    vkMapMemory(device, inMemory, 0, sizeof(input), 0, &mapped);
    std::memcpy(mapped, &input, sizeof(input));
    vkUnmapMemory(device, inMemory);
    vkMapMemory(device, outMemory, 0, sizeof(output), 0, &mapped);
    std::memcpy(mapped, &output, sizeof(output));
    vkUnmapMemory(device, outMemory);

    VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    smci.codeSize = shaderBytes.size();
    smci.pCode = reinterpret_cast<const uint32_t*>(shaderBytes.data());
    res = vkCreateShaderModule(device, &smci, nullptr, &shader);
    if (res != VK_SUCCESS) { out.error = "vkCreateShaderModule failed " + vk_result_to_string(res); cleanup(); return out; }

    VkDescriptorSetLayoutBinding bindings[2]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1] = bindings[0];
    bindings[1].binding = 1;
    VkDescriptorSetLayoutCreateInfo dlci{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dlci.bindingCount = 2;
    dlci.pBindings = bindings;
    res = vkCreateDescriptorSetLayout(device, &dlci, nullptr, &layout);
    if (res != VK_SUCCESS) { out.error = "vkCreateDescriptorSetLayout failed"; cleanup(); return out; }

    VkPipelineLayoutCreateInfo plci{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    plci.setLayoutCount = 1;
    plci.pSetLayouts = &layout;
    res = vkCreatePipelineLayout(device, &plci, nullptr, &pipelineLayout);
    if (res != VK_SUCCESS) { out.error = "vkCreatePipelineLayout failed"; cleanup(); return out; }

    VkPipelineShaderStageCreateInfo stage{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stage.module = shader;
    stage.pName = "main";
    VkComputePipelineCreateInfo cpci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    cpci.stage = stage;
    cpci.layout = pipelineLayout;
    res = vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &cpci, nullptr, &pipeline);
    if (res != VK_SUCCESS) { out.error = "vkCreateComputePipelines failed " + vk_result_to_string(res); cleanup(); return out; }

    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 2;
    VkDescriptorPoolCreateInfo dpci{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dpci.maxSets = 1;
    dpci.poolSizeCount = 1;
    dpci.pPoolSizes = &poolSize;
    res = vkCreateDescriptorPool(device, &dpci, nullptr, &descriptorPool);
    if (res != VK_SUCCESS) { out.error = "vkCreateDescriptorPool failed"; cleanup(); return out; }

    VkDescriptorSetAllocateInfo dsai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    dsai.descriptorPool = descriptorPool;
    dsai.descriptorSetCount = 1;
    dsai.pSetLayouts = &layout;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    res = vkAllocateDescriptorSets(device, &dsai, &descriptorSet);
    if (res != VK_SUCCESS) { out.error = "vkAllocateDescriptorSets failed"; cleanup(); return out; }

    VkDescriptorBufferInfo inInfo{inBuffer, 0, sizeof(GpuInput)};
    VkDescriptorBufferInfo outInfo{outBuffer, 0, sizeof(GpuOutput)};
    VkWriteDescriptorSet writes[2]{};
    for (int i = 0; i < 2; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = descriptorSet;
        writes[i].dstBinding = uint32_t(i);
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    }
    writes[0].pBufferInfo = &inInfo;
    writes[1].pBufferInfo = &outInfo;
    vkUpdateDescriptorSets(device, 2, writes, 0, nullptr);

    VkCommandPoolCreateInfo cpi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    cpi.queueFamilyIndex = queueFamily;
    res = vkCreateCommandPool(device, &cpi, nullptr, &commandPool);
    if (res != VK_SUCCESS) { out.error = "vkCreateCommandPool failed"; cleanup(); return out; }

    VkCommandBufferAllocateInfo cbai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cbai.commandPool = commandPool;
    cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbai.commandBufferCount = 1;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    res = vkAllocateCommandBuffers(device, &cbai, &cmd);
    if (res != VK_SUCCESS) { out.error = "vkAllocateCommandBuffers failed"; cleanup(); return out; }

    VkCommandBufferBeginInfo cbbi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    vkBeginCommandBuffer(cmd, &cbbi);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, 1, &descriptorSet, 0, nullptr);
    uint32_t groups = (count + 127u) / 128u;
    vkCmdDispatch(cmd, groups, 1, 1);
    vkEndCommandBuffer(cmd);

    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    res = vkQueueSubmit(queue, 1, &submit, VK_NULL_HANDLE);
    if (res != VK_SUCCESS) { out.error = "vkQueueSubmit failed"; cleanup(); return out; }
    vkQueueWaitIdle(queue);

    vkMapMemory(device, outMemory, 0, sizeof(output), 0, &mapped);
    std::memcpy(&output, mapped, sizeof(output));
    vkUnmapMemory(device, outMemory);

    out.hashesDone = output.hashes;
    if (output.found) {
        out.found = true;
        out.nonce = output.nonce;
        for (int i = 0; i < 8; ++i) write_be32(out.hash + i * 4, output.hash[i]);
    }
    cleanup();
    return out;
}

// Minimal OpenCL 1.1 dynamic binding. Android does not ship official OpenCL headers/libs,
// so this uses vendor libOpenCL.so when the device exposes it.
typedef intptr_t cl_context_properties;
typedef uint32_t cl_uint;
typedef int32_t cl_int;
typedef uint64_t cl_ulong;
typedef uint64_t cl_device_type;
typedef uint64_t cl_mem_flags;
typedef struct _cl_platform_id* cl_platform_id;
typedef struct _cl_device_id* cl_device_id;
typedef struct _cl_context* cl_context;
typedef struct _cl_command_queue* cl_command_queue;
typedef struct _cl_program* cl_program;
typedef struct _cl_kernel* cl_kernel;
typedef struct _cl_mem* cl_mem;

constexpr cl_int CL_SUCCESS = 0;
constexpr cl_device_type CL_DEVICE_TYPE_GPU = (1 << 2);
constexpr cl_mem_flags CL_MEM_READ_WRITE = (1 << 0);
constexpr cl_mem_flags CL_MEM_WRITE_ONLY = (1 << 1);
constexpr cl_mem_flags CL_MEM_READ_ONLY = (1 << 2);
constexpr cl_mem_flags CL_MEM_COPY_HOST_PTR = (1 << 5);
constexpr cl_uint CL_TRUE = 1;

struct OpenCLApi {
    void* lib = nullptr;
    cl_int (*clGetPlatformIDs)(cl_uint, cl_platform_id*, cl_uint*) = nullptr;
    cl_int (*clGetDeviceIDs)(cl_platform_id, cl_device_type, cl_uint, cl_device_id*, cl_uint*) = nullptr;
    cl_context (*clCreateContext)(const cl_context_properties*, cl_uint, const cl_device_id*, void (*)(const char*, const void*, size_t, void*), void*, cl_int*) = nullptr;
    cl_command_queue (*clCreateCommandQueue)(cl_context, cl_device_id, cl_ulong, cl_int*) = nullptr;
    cl_program (*clCreateProgramWithSource)(cl_context, cl_uint, const char**, const size_t*, cl_int*) = nullptr;
    cl_int (*clBuildProgram)(cl_program, cl_uint, const cl_device_id*, const char*, void (*)(cl_program, void*), void*) = nullptr;
    cl_kernel (*clCreateKernel)(cl_program, const char*, cl_int*) = nullptr;
    cl_mem (*clCreateBuffer)(cl_context, cl_mem_flags, size_t, void*, cl_int*) = nullptr;
    cl_int (*clSetKernelArg)(cl_kernel, cl_uint, size_t, const void*) = nullptr;
    cl_int (*clEnqueueNDRangeKernel)(cl_command_queue, cl_kernel, cl_uint, const size_t*, const size_t*, const size_t*, cl_uint, const void*, void*) = nullptr;
    cl_int (*clEnqueueReadBuffer)(cl_command_queue, cl_mem, cl_uint, size_t, size_t, void*, cl_uint, const void*, void*) = nullptr;
    cl_int (*clFinish)(cl_command_queue) = nullptr;
    cl_int (*clReleaseMemObject)(cl_mem) = nullptr;
    cl_int (*clReleaseKernel)(cl_kernel) = nullptr;
    cl_int (*clReleaseProgram)(cl_program) = nullptr;
    cl_int (*clReleaseCommandQueue)(cl_command_queue) = nullptr;
    cl_int (*clReleaseContext)(cl_context) = nullptr;
};

template <typename T>
bool load_sym(void* lib, const char* name, T& fn) {
    fn = reinterpret_cast<T>(dlsym(lib, name));
    return fn != nullptr;
}

bool load_opencl(OpenCLApi& api, std::string& err) {
    const char* libs[] = {
        "libOpenCL.so",
        "/system/vendor/lib64/libOpenCL.so",
        "/system/vendor/lib/libOpenCL.so",
        "/vendor/lib64/libOpenCL.so",
        "/vendor/lib/libOpenCL.so"
    };
    for (const char* path : libs) {
        api.lib = dlopen(path, RTLD_NOW | RTLD_LOCAL);
        if (api.lib) break;
    }
    if (!api.lib) { err = "OpenCL libOpenCL.so not exposed by this Android ROM/vendor"; return false; }
    bool ok = true;
    ok &= load_sym(api.lib, "clGetPlatformIDs", api.clGetPlatformIDs);
    ok &= load_sym(api.lib, "clGetDeviceIDs", api.clGetDeviceIDs);
    ok &= load_sym(api.lib, "clCreateContext", api.clCreateContext);
    ok &= load_sym(api.lib, "clCreateCommandQueue", api.clCreateCommandQueue);
    ok &= load_sym(api.lib, "clCreateProgramWithSource", api.clCreateProgramWithSource);
    ok &= load_sym(api.lib, "clBuildProgram", api.clBuildProgram);
    ok &= load_sym(api.lib, "clCreateKernel", api.clCreateKernel);
    ok &= load_sym(api.lib, "clCreateBuffer", api.clCreateBuffer);
    ok &= load_sym(api.lib, "clSetKernelArg", api.clSetKernelArg);
    ok &= load_sym(api.lib, "clEnqueueNDRangeKernel", api.clEnqueueNDRangeKernel);
    ok &= load_sym(api.lib, "clEnqueueReadBuffer", api.clEnqueueReadBuffer);
    ok &= load_sym(api.lib, "clFinish", api.clFinish);
    ok &= load_sym(api.lib, "clReleaseMemObject", api.clReleaseMemObject);
    ok &= load_sym(api.lib, "clReleaseKernel", api.clReleaseKernel);
    ok &= load_sym(api.lib, "clReleaseProgram", api.clReleaseProgram);
    ok &= load_sym(api.lib, "clReleaseCommandQueue", api.clReleaseCommandQueue);
    ok &= load_sym(api.lib, "clReleaseContext", api.clReleaseContext);
    if (!ok) { err = "OpenCL library is present but required symbols are missing"; return false; }
    return true;
}

const char* OPENCL_KERNEL = R"CLC(
uint rotr(uint x, uint n) { return (x >> n) | (x << (32u - n)); }
uint ch(uint x, uint y, uint z) { return (x & y) ^ ((~x) & z); }
uint maj(uint x, uint y, uint z) { return (x & y) ^ (x & z) ^ (y & z); }
uint bsig0(uint x) { return rotr(x, 2u) ^ rotr(x, 13u) ^ rotr(x, 22u); }
uint bsig1(uint x) { return rotr(x, 6u) ^ rotr(x, 11u) ^ rotr(x, 25u); }
uint ssig0(uint x) { return rotr(x, 7u) ^ rotr(x, 18u) ^ (x >> 3u); }
uint ssig1(uint x) { return rotr(x, 17u) ^ rotr(x, 19u) ^ (x >> 10u); }
__constant uint K[64] = {
0x428a2f98u,0x71374491u,0xb5c0fbcfu,0xe9b5dba5u,0x3956c25bu,0x59f111f1u,0x923f82a4u,0xab1c5ed5u,
0xd807aa98u,0x12835b01u,0x243185beu,0x550c7dc3u,0x72be5d74u,0x80deb1feu,0x9bdc06a7u,0xc19bf174u,
0xe49b69c1u,0xefbe4786u,0x0fc19dc6u,0x240ca1ccu,0x2de92c6fu,0x4a7484aau,0x5cb0a9dcu,0x76f988dau,
0x983e5152u,0xa831c66du,0xb00327c8u,0xbf597fc7u,0xc6e00bf3u,0xd5a79147u,0x06ca6351u,0x14292967u,
0x27b70a85u,0x2e1b2138u,0x4d2c6dfcu,0x53380d13u,0x650a7354u,0x766a0abbu,0x81c2c92eu,0x92722c85u,
0xa2bfe8a1u,0xa81a664bu,0xc24b8b70u,0xc76c51a3u,0xd192e819u,0xd6990624u,0xf40e3585u,0x106aa070u,
0x19a4c116u,0x1e376c08u,0x2748774cu,0x34b0bcb5u,0x391c0cb3u,0x4ed8aa4au,0x5b9cca4fu,0x682e6ff3u,
0x748f82eeu,0x78a5636fu,0x84c87814u,0x8cc70208u,0x90befffau,0xa4506cebu,0xbef9a3f7u,0xc67178f2u};
void compress(uint st[8], uint block[16]) {
    uint w[64];
    for (int i=0;i<16;i++) w[i]=block[i];
    for (int i=16;i<64;i++) w[i]=ssig1(w[i-2])+w[i-7]+ssig0(w[i-15])+w[i-16];
    uint a=st[0],b=st[1],c=st[2],d=st[3],e=st[4],f=st[5],g=st[6],h=st[7];
    for (int i=0;i<64;i++) { uint t1=h+bsig1(e)+ch(e,f,g)+K[i]+w[i]; uint t2=bsig0(a)+maj(a,b,c); h=g; g=f; f=e; e=d+t1; d=c; c=b; b=a; a=t1+t2; }
    st[0]+=a; st[1]+=b; st[2]+=c; st[3]+=d; st[4]+=e; st[5]+=f; st[6]+=g; st[7]+=h;
}
void sha256d(__global const uint* in, uint nonce, uint digest[8]) {
    uint st[8]={0x6a09e667u,0xbb67ae85u,0x3c6ef372u,0xa54ff53au,0x510e527fu,0x9b05688cu,0x1f83d9abu,0x5be0cd19u};
    uint block[16];
    for(int i=0;i<16;i++) block[i]=in[i];
    compress(st,block);
    block[0]=in[16]; block[1]=in[17]; block[2]=in[18];
    block[3]=((nonce&0xffu)<<24u)|(((nonce>>8u)&0xffu)<<16u)|(((nonce>>16u)&0xffu)<<8u)|((nonce>>24u)&0xffu);
    block[4]=0x80000000u; for(int i=5;i<15;i++) block[i]=0u; block[15]=640u;
    compress(st,block);
    uint st2[8]={0x6a09e667u,0xbb67ae85u,0x3c6ef372u,0xa54ff53au,0x510e527fu,0x9b05688cu,0x1f83d9abu,0x5be0cd19u};
    for(int i=0;i<8;i++) block[i]=st[i]; block[8]=0x80000000u; for(int i=9;i<15;i++) block[i]=0u; block[15]=256u;
    compress(st2,block); for(int i=0;i<8;i++) digest[i]=st2[i];
}
int meets(__global const uint* in, uint h[8]) { for(int i=0;i<8;i++){ uint t=in[20+i]; if(h[i]<t) return 1; if(h[i]>t) return 0; } return 1; }
__kernel void mine(__global const uint* in, __global uint* out) {
    uint gid=(uint)get_global_id(0); uint count=in[29]; if(gid>=count) return; if(out[0]!=0u) return;
    uint nonce=in[28]+gid; uint h[8]; sha256d(in,nonce,h); atomic_inc((volatile __global unsigned int*)&out[10]);
    if(meets(in,h)) { if(atomic_cmpxchg((volatile __global unsigned int*)&out[0],0u,1u)==0u) { out[1]=nonce; for(int i=0;i<8;i++) out[2+i]=h[i]; } }
}
)CLC";

ScanResult scan_opencl(const uint8_t header[80], const uint8_t target[32], uint64_t startNonce, uint32_t count) {
    ScanResult r;
    r.backendUsed = "OpenCL";
    OpenCLApi api;
    std::string err;
    if (!load_opencl(api, err)) { r.error = err; return r; }

    cl_uint platformCount = 0;
    cl_int e = api.clGetPlatformIDs(0, nullptr, &platformCount);
    if (e != CL_SUCCESS || platformCount == 0) { r.error = "OpenCL has no platform"; return r; }
    std::vector<cl_platform_id> platforms(platformCount);
    api.clGetPlatformIDs(platformCount, platforms.data(), nullptr);
    cl_device_id device = nullptr;
    for (auto p : platforms) {
        cl_uint deviceCount = 0;
        if (api.clGetDeviceIDs(p, CL_DEVICE_TYPE_GPU, 0, nullptr, &deviceCount) == CL_SUCCESS && deviceCount > 0) {
            std::vector<cl_device_id> devices(deviceCount);
            if (api.clGetDeviceIDs(p, CL_DEVICE_TYPE_GPU, deviceCount, devices.data(), nullptr) == CL_SUCCESS) { device = devices[0]; break; }
        }
    }
    if (!device) { r.error = "OpenCL GPU device not found"; return r; }

    cl_context ctx = nullptr; cl_command_queue q = nullptr; cl_program program = nullptr; cl_kernel kernel = nullptr; cl_mem inBuf = nullptr; cl_mem outBuf = nullptr;
    auto cleanup = [&]() {
        if (inBuf) api.clReleaseMemObject(inBuf);
        if (outBuf) api.clReleaseMemObject(outBuf);
        if (kernel) api.clReleaseKernel(kernel);
        if (program) api.clReleaseProgram(program);
        if (q) api.clReleaseCommandQueue(q);
        if (ctx) api.clReleaseContext(ctx);
    };

    ctx = api.clCreateContext(nullptr, 1, &device, nullptr, nullptr, &e);
    if (e != CL_SUCCESS || !ctx) { r.error = "clCreateContext failed"; cleanup(); return r; }
    q = api.clCreateCommandQueue(ctx, device, 0, &e);
    if (e != CL_SUCCESS || !q) { r.error = "clCreateCommandQueue failed"; cleanup(); return r; }
    const char* src = OPENCL_KERNEL;
    size_t srcLen = std::strlen(OPENCL_KERNEL);
    program = api.clCreateProgramWithSource(ctx, 1, &src, &srcLen, &e);
    if (e != CL_SUCCESS || !program) { r.error = "clCreateProgramWithSource failed"; cleanup(); return r; }
    e = api.clBuildProgram(program, 1, &device, "", nullptr, nullptr);
    if (e != CL_SUCCESS) { r.error = "OpenCL kernel build failed"; cleanup(); return r; }
    kernel = api.clCreateKernel(program, "mine", &e);
    if (e != CL_SUCCESS || !kernel) { r.error = "clCreateKernel failed"; cleanup(); return r; }

    uint32_t input[30]{};
    prepare_words(header, target, input);
    input[28] = uint32_t(startNonce);
    input[29] = count;
    uint32_t output[11]{};

    inBuf = api.clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, sizeof(input), input, &e);
    if (e != CL_SUCCESS || !inBuf) { r.error = "clCreateBuffer input failed"; cleanup(); return r; }
    outBuf = api.clCreateBuffer(ctx, CL_MEM_READ_WRITE | CL_MEM_COPY_HOST_PTR, sizeof(output), output, &e);
    if (e != CL_SUCCESS || !outBuf) { r.error = "clCreateBuffer output failed"; cleanup(); return r; }
    api.clSetKernelArg(kernel, 0, sizeof(cl_mem), &inBuf);
    api.clSetKernelArg(kernel, 1, sizeof(cl_mem), &outBuf);
    size_t local = 128;
    size_t global = ((size_t(count) + local - 1) / local) * local;
    e = api.clEnqueueNDRangeKernel(q, kernel, 1, nullptr, &global, &local, 0, nullptr, nullptr);
    if (e != CL_SUCCESS) { r.error = "clEnqueueNDRangeKernel failed"; cleanup(); return r; }
    api.clFinish(q);
    e = api.clEnqueueReadBuffer(q, outBuf, CL_TRUE, 0, sizeof(output), output, 0, nullptr, nullptr);
    if (e != CL_SUCCESS) { r.error = "clEnqueueReadBuffer failed"; cleanup(); return r; }

    r.hashesDone = output[10] ? output[10] : count;
    if (output[0]) {
        r.found = true;
        r.nonce = output[1];
        for (int i = 0; i < 8; ++i) write_be32(r.hash + i * 4, output[2 + i]);
    }
    cleanup();
    return r;
}

std::string describe_vulkan() {
    std::ostringstream oss;
    VkInstance instance = VK_NULL_HANDLE;
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "TrueMiner";
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    if (vkCreateInstance(&ici, nullptr, &instance) != VK_SUCCESS) return "Vulkan: unavailable";
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(instance, &count, nullptr);
    oss << "Vulkan: " << count << " device(s)";
    if (count > 0) {
        std::vector<VkPhysicalDevice> devices(count);
        vkEnumeratePhysicalDevices(instance, &count, devices.data());
        for (auto d : devices) {
            VkPhysicalDeviceProperties props{};
            vkGetPhysicalDeviceProperties(d, &props);
            oss << "\n• " << props.deviceName;
        }
    }
    vkDestroyInstance(instance, nullptr);
    return oss.str();
}

std::string describe_opencl() {
    OpenCLApi api;
    std::string err;
    if (!load_opencl(api, err)) return "OpenCL: " + err;
    cl_uint platformCount = 0;
    if (api.clGetPlatformIDs(0, nullptr, &platformCount) != CL_SUCCESS || platformCount == 0) return "OpenCL: no platforms";
    return "OpenCL: library present, platforms=" + std::to_string(platformCount);
}

jobject make_result(JNIEnv* env, const ScanResult& r) {
    jclass cls = env->FindClass("com/trueminer/mining/NativeScanResult");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ZJ[BJLjava/lang/String;Ljava/lang/String;)V");
    jbyteArray hashArray = env->NewByteArray(32);
    env->SetByteArrayRegion(hashArray, 0, 32, reinterpret_cast<const jbyte*>(r.hash));
    jstring backend = env->NewStringUTF(r.backendUsed.c_str());
    jstring error = env->NewStringUTF(r.error.c_str());
    return env->NewObject(cls, ctor, r.found ? JNI_TRUE : JNI_FALSE, static_cast<jlong>(uint64_t(r.nonce)), hashArray, static_cast<jlong>(r.hashesDone), backend, error);
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_trueminer_mining_NativeMiner_nativeDescribeDevices(JNIEnv* env, jobject /*thiz*/, jobject /*assetManager*/) {
    std::string info = describe_vulkan() + "\n" + describe_opencl();
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_trueminer_mining_NativeMiner_nativeScan(JNIEnv* env, jobject /*thiz*/, jbyteArray headerArray, jbyteArray targetArray,
                                                 jlong startNonce, jint count, jint backendId, jobject assetManagerObj) {
    ScanResult invalid;
    invalid.backendUsed = "native";
    if (!headerArray || !targetArray || env->GetArrayLength(headerArray) < 80 || env->GetArrayLength(targetArray) < 32) {
        invalid.error = "Invalid header/target buffers";
        return make_result(env, invalid);
    }
    if (count <= 0) {
        invalid.error = "Invalid scan count";
        return make_result(env, invalid);
    }

    uint8_t header[80];
    uint8_t target[32];
    env->GetByteArrayRegion(headerArray, 0, 80, reinterpret_cast<jbyte*>(header));
    env->GetByteArrayRegion(targetArray, 0, 32, reinterpret_cast<jbyte*>(target));
    AAssetManager* mgr = assetManagerObj ? AAssetManager_fromJava(env, assetManagerObj) : nullptr;

    ScanResult r;
    // Kotlin ordinal: AUTO=0, VULKAN=1, OPENCL=2
    if (backendId == 1) {
        r = scan_vulkan(header, target, static_cast<uint64_t>(startNonce), static_cast<uint32_t>(count), mgr);
    } else if (backendId == 2) {
        r = scan_opencl(header, target, static_cast<uint64_t>(startNonce), static_cast<uint32_t>(count));
    } else {
        r = scan_vulkan(header, target, static_cast<uint64_t>(startNonce), static_cast<uint32_t>(count), mgr);
        if (!r.error.empty()) {
            ScanResult cl = scan_opencl(header, target, static_cast<uint64_t>(startNonce), static_cast<uint32_t>(count));
            if (cl.error.empty()) r = cl;
            else {
                ScanResult cpu = scan_cpu(header, target, static_cast<uint64_t>(startNonce), static_cast<uint32_t>(count));
                cpu.error = "GPU unavailable; used native CPU fallback. Vulkan: " + r.error + " OpenCL: " + cl.error;
                r = cpu;
            }
        }
    }
    return make_result(env, r);
}
