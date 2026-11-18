package com.clientm.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDLVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * The Vulkan device, the window's swapchain and the frame loop.
 *
 * One command buffer is recorded per game frame. Everything the game draws during the frame goes into it, in the
 * order the game asked for it. At the end of the frame the game's screen image (the "default framebuffer", which the
 * game draws into like OpenGL's back buffer) is copied to the window, turned the right way up, and shown.
 *
 * Two frames can be in flight: while the graphics card works on one, the game records the next. The CPU only waits
 * when it is two frames ahead.
 *
 * All images are kept in the GENERAL layout and work is ordered with full barriers. That costs a little against
 * perfectly tuned layouts, and keeps the OpenGL-style "use anything anywhere" rules simple and correct.
 */
@Lwjgl3Aware
final class VulkanDevice {

    private static final Logger LOG = LogManager.getLogger("ClientM/Vulkan");
    static final int FRAMES_IN_FLIGHT = 2;

    private final long window;
    private VkInstance instance;
    private long surface;
    private VkPhysicalDevice gpu;
    private String gpuName = "unknown";
    private VkDevice device;
    private int queueFamily = -1;
    private VkQueue queue;
    private long commandPool;
    private final VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.calloc();
    private float maxAnisotropy = 1f;
    private int depthFormat = VK_FORMAT_D24_UNORM_S8_UINT;

    private long swapchain;
    private int swapWidth, swapHeight;
    private long[] swapImages = new long[0];
    private int presentMode;
    private boolean needsRebuild;

    private final VkCommandBuffer[] commands = new VkCommandBuffer[FRAMES_IN_FLIGHT];
    private final long[] imageReady = new long[FRAMES_IN_FLIGHT];
    private final long[] frameDone = new long[FRAMES_IN_FLIGHT];
    private final long[] slotFrame = new long[FRAMES_IN_FLIGHT];
    private long[] renderDone = new long[0];
    @SuppressWarnings("unchecked")
    private final List<Runnable>[] deferred = new List[FRAMES_IN_FLIGHT];
    private long frameNumber = 1;
    private long completedFrame;
    private int slot;
    private boolean recording;

    /** Called just before a frame is submitted, so open drawing work can be closed. */
    Runnable beforeSubmit = () -> {};

    VulkanDevice(long window) {
        this.window = window;
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) deferred[i] = new ArrayList<>();
    }

    String gpuName() {
        return gpuName;
    }

    VkDevice vk() {
        return device;
    }

    float maxAnisotropy() {
        return maxAnisotropy;
    }

    int depthFormat() {
        return depthFormat;
    }

    // ------------------------------------------------------------------ setup

    void create() {
        try (MemoryStack stack = stackPush()) {
            createInstance(stack);
            LongBuffer pSurface = stack.mallocLong(1);
            if (!SDLVulkan.SDL_Vulkan_CreateSurface(window, instance, null, pSurface)) {
                throw new IllegalStateException("SDL could not create a Vulkan surface for the window");
            }
            surface = pSurface.get(0);
            pickGpu(stack);
            createDevice(stack);
            createCommands(stack);
        }
        vkGetPhysicalDeviceMemoryProperties(gpu, memory);
        createSwapchain();
        LOG.info(
            "Vulkan ready on {}: {}x{}, {} swapchain images, present mode {}",
            gpuName,
            swapWidth,
            swapHeight,
            swapImages.length,
            presentModeName(presentMode));
    }

    private void createInstance(MemoryStack stack) {
        PointerBuffer sdlExtensions = SDLVulkan.SDL_Vulkan_GetInstanceExtensions();
        if (sdlExtensions == null) throw new IllegalStateException("SDL reports no Vulkan support for this window");
        PointerBuffer extensions = stack.mallocPointer(sdlExtensions.remaining());
        for (int i = 0; i < sdlExtensions.remaining(); i++)
            extensions.put(i, sdlExtensions.get(sdlExtensions.position() + i));
        VkApplicationInfo app = VkApplicationInfo.calloc(stack)
            .sType$Default()
            .pApplicationName(stack.UTF8("ClientM"))
            .pEngineName(stack.UTF8("ClientM Vulkan"))
            .apiVersion(VK13.VK_API_VERSION_1_3);
        VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack)
            .sType$Default()
            .pApplicationInfo(app)
            .ppEnabledExtensionNames(extensions);
        PointerBuffer pInstance = stack.mallocPointer(1);
        check(vkCreateInstance(info, null, pInstance), "create the Vulkan instance");
        instance = new VkInstance(pInstance.get(0), info);
    }

    private void pickGpu(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        check(vkEnumeratePhysicalDevices(instance, count, null), "list graphics cards");
        PointerBuffer handles = stack.mallocPointer(count.get(0));
        check(vkEnumeratePhysicalDevices(instance, count, handles), "list graphics cards");
        VkPhysicalDevice fallback = null;
        int fallbackFamily = -1;
        String fallbackName = null;
        for (int i = 0; i < count.get(0); i++) {
            VkPhysicalDevice candidate = new VkPhysicalDevice(handles.get(i), instance);
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(candidate, props);
            if (VK_API_VERSION_MAJOR(props.apiVersion()) < 1 || VK_API_VERSION_MINOR(props.apiVersion()) < 3) continue;
            int family = graphicsAndPresentFamily(candidate, stack);
            if (family < 0) continue;
            if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
                gpu = candidate;
                queueFamily = family;
                gpuName = props.deviceNameString();
                maxAnisotropy = props.limits()
                    .maxSamplerAnisotropy();
                return;
            }
            if (fallback == null) {
                fallback = candidate;
                fallbackFamily = family;
                fallbackName = props.deviceNameString();
                maxAnisotropy = props.limits()
                    .maxSamplerAnisotropy();
            }
        }
        if (fallback == null) throw new IllegalStateException("No graphics card with Vulkan 1.3 can show this window");
        gpu = fallback;
        queueFamily = fallbackFamily;
        gpuName = fallbackName;
    }

    private int graphicsAndPresentFamily(VkPhysicalDevice candidate, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
        IntBuffer supported = stack.mallocInt(1);
        for (int i = 0; i < count.get(0); i++) {
            if ((families.get(i)
                .queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0) continue;
            vkGetPhysicalDeviceSurfaceSupportKHR(candidate, i, surface, supported);
            if (supported.get(0) == VK_TRUE) return i;
        }
        return -1;
    }

    private void createDevice(MemoryStack stack) {
        VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, stack);
        queues.get(0)
            .sType$Default()
            .queueFamilyIndex(queueFamily)
            .pQueuePriorities(stack.floats(1.0f));

        VkPhysicalDeviceFeatures supported = VkPhysicalDeviceFeatures.calloc(stack);
        vkGetPhysicalDeviceFeatures(gpu, supported);
        VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack)
            .samplerAnisotropy(supported.samplerAnisotropy())
            .fillModeNonSolid(supported.fillModeNonSolid())
            .wideLines(supported.wideLines())
            .independentBlend(supported.independentBlend())
            .multiDrawIndirect(supported.multiDrawIndirect())
            .drawIndirectFirstInstance(supported.drawIndirectFirstInstance())
            .depthClamp(supported.depthClamp())
            .shaderClipDistance(supported.shaderClipDistance())
            .logicOp(supported.logicOp());
        VkPhysicalDeviceVulkan13Features v13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
            .sType$Default()
            .dynamicRendering(true)
            .synchronization2(true);
        VkPhysicalDeviceVulkan12Features v12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
            .sType$Default()
            .drawIndirectCount(true)
            .pNext(v13.address());
        VkPhysicalDeviceVulkan11Features v11 = VkPhysicalDeviceVulkan11Features.calloc(stack)
            .sType$Default()
            .shaderDrawParameters(true)
            .pNext(v12.address());

        VkDeviceCreateInfo info = VkDeviceCreateInfo.calloc(stack)
            .sType$Default()
            .pNext(v11.address())
            .pQueueCreateInfos(queues)
            .pEnabledFeatures(features)
            .ppEnabledExtensionNames(
                stack.pointers(
                    stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME),
                    stack.UTF8(KHRPushDescriptor.VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME)));
        PointerBuffer pDevice = stack.mallocPointer(1);
        check(vkCreateDevice(gpu, info, null, pDevice), "open the graphics card");
        device = new VkDevice(pDevice.get(0), gpu, info);
        PointerBuffer pQueue = stack.mallocPointer(1);
        vkGetDeviceQueue(device, queueFamily, 0, pQueue);
        queue = new VkQueue(pQueue.get(0), device);

        VkFormatProperties fp = VkFormatProperties.calloc(stack);
        vkGetPhysicalDeviceFormatProperties(gpu, VK_FORMAT_D24_UNORM_S8_UINT, fp);
        if ((fp.optimalTilingFeatures() & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) == 0) {
            depthFormat = VK_FORMAT_D32_SFLOAT_S8_UINT;
        }
    }

    private void createCommands(MemoryStack stack) {
        VkCommandPoolCreateInfo pool = VkCommandPoolCreateInfo.calloc(stack)
            .sType$Default()
            .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
            .queueFamilyIndex(queueFamily);
        LongBuffer handle = stack.mallocLong(1);
        check(vkCreateCommandPool(device, pool, null, handle), "create a command pool");
        commandPool = handle.get(0);
        VkCommandBufferAllocateInfo alloc = VkCommandBufferAllocateInfo.calloc(stack)
            .sType$Default()
            .commandPool(commandPool)
            .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
            .commandBufferCount(FRAMES_IN_FLIGHT);
        PointerBuffer pCommands = stack.mallocPointer(FRAMES_IN_FLIGHT);
        check(vkAllocateCommandBuffers(device, alloc, pCommands), "allocate command buffers");
        VkSemaphoreCreateInfo semaphore = VkSemaphoreCreateInfo.calloc(stack)
            .sType$Default();
        VkFenceCreateInfo fence = VkFenceCreateInfo.calloc(stack)
            .sType$Default()
            .flags(VK_FENCE_CREATE_SIGNALED_BIT);
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            commands[i] = new VkCommandBuffer(pCommands.get(i), device);
            check(vkCreateSemaphore(device, semaphore, null, handle), "create a semaphore");
            imageReady[i] = handle.get(0);
            check(vkCreateFence(device, fence, null, handle), "create a fence");
            frameDone[i] = handle.get(0);
        }
    }

    private void createSwapchain() {
        try (MemoryStack stack = stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(gpu, surface, caps), "read the window surface");
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            SDLVideo.SDL_GetWindowSizeInPixels(window, w, h);
            int ew, eh;
            if (caps.currentExtent()
                .width() != 0xFFFFFFFF) {
                ew = caps.currentExtent()
                    .width();
                eh = caps.currentExtent()
                    .height();
            } else {
                ew = clamp(
                    w.get(0),
                    caps.minImageExtent()
                        .width(),
                    caps.maxImageExtent()
                        .width());
                eh = clamp(
                    h.get(0),
                    caps.minImageExtent()
                        .height(),
                    caps.maxImageExtent()
                        .height());
            }
            if (ew == 0 || eh == 0) {
                needsRebuild = true;
                return;
            }
            IntBuffer count = stack.mallocInt(1);
            vkGetPhysicalDeviceSurfaceFormatsKHR(gpu, surface, count, null);
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(gpu, surface, count, formats);
            int chosen = formats.get(0)
                .format();
            int colorSpace = formats.get(0)
                .colorSpace();
            for (int i = 0; i < count.get(0); i++) {
                if (formats.get(i)
                    .format() == VK_FORMAT_B8G8R8A8_UNORM) {
                    chosen = VK_FORMAT_B8G8R8A8_UNORM;
                    colorSpace = formats.get(i)
                        .colorSpace();
                    break;
                }
            }
            presentMode = choosePresentMode(stack);
            int imageCount = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0) imageCount = Math.min(imageCount, caps.maxImageCount());
            long old = swapchain;
            VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.calloc(stack)
                .sType$Default()
                .surface(surface)
                .minImageCount(imageCount)
                .imageFormat(chosen)
                .imageColorSpace(colorSpace)
                .imageExtent(
                    VkExtent2D.calloc(stack)
                        .set(ew, eh))
                .imageArrayLayers(1)
                .imageUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .preTransform(caps.currentTransform())
                .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                .presentMode(presentMode)
                .clipped(true)
                .oldSwapchain(old);
            LongBuffer pSwapchain = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(device, info, null, pSwapchain), "create the swapchain");
            swapchain = pSwapchain.get(0);
            if (old != VK_NULL_HANDLE) vkDestroySwapchainKHR(device, old, null);
            vkGetSwapchainImagesKHR(device, swapchain, count, null);
            LongBuffer pImages = stack.mallocLong(count.get(0));
            vkGetSwapchainImagesKHR(device, swapchain, count, pImages);
            swapImages = new long[count.get(0)];
            for (int i = 0; i < swapImages.length; i++) swapImages[i] = pImages.get(i);
            for (long s : renderDone) vkDestroySemaphore(device, s, null);
            renderDone = new long[swapImages.length];
            LongBuffer handle = stack.mallocLong(1);
            for (int i = 0; i < swapImages.length; i++) {
                check(
                    vkCreateSemaphore(
                        device,
                        VkSemaphoreCreateInfo.calloc(stack)
                            .sType$Default(),
                        null,
                        handle),
                    "create a semaphore");
                renderDone[i] = handle.get(0);
            }
            swapWidth = ew;
            swapHeight = eh;
            needsRebuild = false;
        }
    }

    private int choosePresentMode(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceSurfacePresentModesKHR(gpu, surface, count, null);
        IntBuffer modes = stack.mallocInt(count.get(0));
        vkGetPhysicalDeviceSurfacePresentModesKHR(gpu, surface, count, modes);
        boolean mailbox = false;
        for (int i = 0; i < count.get(0); i++) {
            if (modes.get(i) == VK_PRESENT_MODE_IMMEDIATE_KHR) return VK_PRESENT_MODE_IMMEDIATE_KHR;
            if (modes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR) mailbox = true;
        }
        return mailbox ? VK_PRESENT_MODE_MAILBOX_KHR : VK_PRESENT_MODE_FIFO_KHR;
    }

    /** The window's size in pixels, which the game's screen image follows. */
    int[] windowPixels() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            SDLVideo.SDL_GetWindowSizeInPixels(window, w, h);
            return new int[] { Math.max(1, w.get(0)), Math.max(1, h.get(0)) };
        }
    }

    void windowChanged() {
        needsRebuild = true;
    }

    // ------------------------------------------------------------------ memory

    int memoryType(int typeBits, int wanted, int fallback) {
        for (int pass = 0; pass < 2; pass++) {
            int props = pass == 0 ? wanted : fallback;
            for (int i = 0; i < memory.memoryTypeCount(); i++) {
                if ((typeBits & (1 << i)) != 0 && (memory.memoryTypes(i)
                    .propertyFlags() & props) == props) return i;
            }
        }
        throw new IllegalStateException("No suitable graphics memory");
    }

    // ------------------------------------------------------------------ frames

    /** The command buffer of the frame being recorded; starts the frame if needed. */
    VkCommandBuffer cmd() {
        if (!recording) beginFrame();
        return commands[slot];
    }

    /** The number of the frame being recorded (or about to be). Frames count up from 1. */
    long currentFrame() {
        return frameNumber;
    }

    /** True once the graphics card has finished every frame up to and including this one. */
    boolean isDone(long frame) {
        if (frame <= completedFrame) return true;
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            if (slotFrame[i] == frame && slotFrame[i] < frameNumber
                && vkGetFenceStatus(device, frameDone[i]) == VK_SUCCESS) {
                completedFrame = Math.max(completedFrame, frame);
                return true;
            }
        }
        return false;
    }

    /** Waits until the given frame is done on the graphics card. A frame not yet submitted cannot be waited for. */
    void waitFor(long frame) {
        if (frame >= frameNumber) return;
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            if (slotFrame[i] == frame) {
                vkWaitForFences(device, frameDone[i], true, Long.MAX_VALUE);
                completedFrame = Math.max(completedFrame, frame);
            }
        }
    }

    /** Runs the action once the graphics card can no longer be using what the current frame used. */
    void later(Runnable action) {
        if (!recording) beginFrame();
        deferred[slot].add(action);
    }

    private void beginFrame() {
        slot = (int) (frameNumber % FRAMES_IN_FLIGHT);
        vkWaitForFences(device, frameDone[slot], true, Long.MAX_VALUE);
        completedFrame = Math.max(completedFrame, slotFrame[slot]);
        List<Runnable> work = deferred[slot];
        if (!work.isEmpty()) {
            Runnable[] copy = work.toArray(new Runnable[0]);
            work.clear();
            for (Runnable r : copy) r.run();
        }
        slotFrame[slot] = frameNumber;
        vkResetCommandBuffer(commands[slot], 0);
        try (MemoryStack stack = stackPush()) {
            vkBeginCommandBuffer(
                commands[slot],
                VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
        }
        recording = true;
    }

    /**
     * Ends the frame: the game's screen image is copied to the window (flipped, since OpenGL keeps row 0 at the
     * bottom), the frame is submitted and shown.
     */
    void endFrame(VulkanImage screen) {
        VkCommandBuffer cmd = cmd();
        beforeSubmit.run();
        int index = -1;
        int f = slot;
        try (MemoryStack stack = stackPush()) {
            if (needsRebuild) {
                vkDeviceWaitIdle(device);
                createSwapchain();
            }
            if (!needsRebuild) {
                IntBuffer pIndex = stack.mallocInt(1);
                int acquired = vkAcquireNextImageKHR(
                    device,
                    swapchain,
                    Long.MAX_VALUE,
                    imageReady[f],
                    VK_NULL_HANDLE,
                    pIndex);
                if (acquired == VK_SUCCESS || acquired == VK_SUBOPTIMAL_KHR) index = pIndex.get(0);
                else needsRebuild = true;
            }
            if (index >= 0 && screen != null) {
                Barriers.full(cmd);
                Barriers.image(
                    cmd,
                    swapImages[index],
                    VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                blit.get(0)
                    .srcSubresource(
                        s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .layerCount(1))
                    .dstSubresource(
                        s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .layerCount(1));
                blit.get(0)
                    .srcOffsets(0)
                    .set(0, screen.height, 0);
                blit.get(0)
                    .srcOffsets(1)
                    .set(screen.width, 0, 1);
                blit.get(0)
                    .dstOffsets(1)
                    .set(swapWidth, swapHeight, 1);
                vkCmdBlitImage(
                    cmd,
                    screen.image,
                    VK_IMAGE_LAYOUT_GENERAL,
                    swapImages[index],
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    blit,
                    VK_FILTER_NEAREST);
                Barriers.image(
                    cmd,
                    swapImages[index],
                    VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            } else if (index >= 0) {
                Barriers.image(
                    cmd,
                    swapImages[index],
                    VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED,
                    KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            }
            vkEndCommandBuffer(cmd);
            vkResetFences(device, frameDone[f]);
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                .sType$Default()
                .pCommandBuffers(stack.pointers(cmd));
            if (index >= 0) {
                submit.waitSemaphoreCount(1)
                    .pWaitSemaphores(stack.longs(imageReady[f]))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT))
                    .pSignalSemaphores(stack.longs(renderDone[index]));
            }
            check(vkQueueSubmit(queue, submit, frameDone[f]), "submit a frame");
            if (index >= 0) {
                int presented = vkQueuePresentKHR(
                    queue,
                    VkPresentInfoKHR.calloc(stack)
                        .sType$Default()
                        .pWaitSemaphores(stack.longs(renderDone[index]))
                        .swapchainCount(1)
                        .pSwapchains(stack.longs(swapchain))
                        .pImageIndices(stack.ints(index)));
                if (presented == VK_ERROR_OUT_OF_DATE_KHR || presented == VK_SUBOPTIMAL_KHR) needsRebuild = true;
                else if (presented != VK_SUCCESS) check(presented, "present a frame");
            }
        }
        recording = false;
        frameNumber++;
    }

    void destroy() {
        if (device != null) {
            vkDeviceWaitIdle(device);
            for (List<Runnable> work : deferred) {
                for (Runnable r : work) r.run();
                work.clear();
            }
            for (long s : renderDone) vkDestroySemaphore(device, s, null);
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                if (imageReady[i] != 0) vkDestroySemaphore(device, imageReady[i], null);
                if (frameDone[i] != 0) vkDestroyFence(device, frameDone[i], null);
            }
            if (commandPool != 0) vkDestroyCommandPool(device, commandPool, null);
            if (swapchain != 0) vkDestroySwapchainKHR(device, swapchain, null);
            vkDestroyDevice(device, null);
            device = null;
        }
        if (instance != null) {
            if (surface != 0) vkDestroySurfaceKHR(instance, surface, null);
            vkDestroyInstance(instance, null);
            instance = null;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String presentModeName(int mode) {
        switch (mode) {
            case VK_PRESENT_MODE_IMMEDIATE_KHR:
                return "immediate";
            case VK_PRESENT_MODE_MAILBOX_KHR:
                return "mailbox";
            case VK_PRESENT_MODE_FIFO_KHR:
                return "fifo";
            default:
                return String.valueOf(mode);
        }
    }

    static void check(int result, String what) {
        if (result != VK_SUCCESS)
            throw new IllegalStateException("Vulkan could not " + what + " (error " + result + ")");
    }
}
