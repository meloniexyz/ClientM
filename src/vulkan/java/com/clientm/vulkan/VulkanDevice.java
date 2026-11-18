package com.clientm.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDLVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
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
 * Stage 1 of the ClientM Vulkan renderer: the Vulkan instance, the graphics card, the window surface and the
 * swapchain, and presenting one frame per game frame. Each frame is only cleared to a colour; drawing the game comes
 * in later stages.
 *
 * Everything runs on the thread that owns the window (the game's main thread).
 */
// Calls LWJGL 3 directly, so lwjgl3ify must not redirect it to its LWJGL 2 compatibility layer.
@Lwjgl3Aware
final class VulkanDevice {

    private static final Logger LOG = LogManager.getLogger("ClientM/Vulkan");
    private static final int FRAMES_IN_FLIGHT = 2;

    private final long window;
    private VkInstance instance;
    private long surface;
    private VkPhysicalDevice gpu;
    private String gpuName = "unknown";
    private VkDevice device;
    private int queueFamily = -1;
    private VkQueue queue;
    private long commandPool;

    private long swapchain;
    private int format;
    private int width, height;
    private long[] images = new long[0];
    private int presentMode;

    private final VkCommandBuffer[] commands = new VkCommandBuffer[FRAMES_IN_FLIGHT];
    private final long[] imageReady = new long[FRAMES_IN_FLIGHT];
    private final long[] frameDone = new long[FRAMES_IN_FLIGHT];
    private long[] renderDone = new long[0];
    private int frame;
    private boolean needsRebuild;

    VulkanDevice(long window) {
        this.window = window;
    }

    String gpuName() {
        return gpuName;
    }

    /** Creates everything up to the swapchain. Throws with a readable message when something is missing. */
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
        createSwapchain();
        LOG.info(
            "Vulkan ready on {}: {}x{}, {} swapchain images, present mode {}",
            gpuName,
            width,
            height,
            images.length,
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
            .apiVersion(org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1);
        VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack)
            .sType$Default()
            .pApplicationInfo(app)
            .ppEnabledExtensionNames(extensions);
        PointerBuffer pInstance = stack.mallocPointer(1);
        check(vkCreateInstance(info, null, pInstance), "create the Vulkan instance");
        instance = new VkInstance(pInstance.get(0), info);
    }

    /** The first discrete graphics card that can draw to this window; otherwise any card that can. */
    private void pickGpu(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        check(vkEnumeratePhysicalDevices(instance, count, null), "list graphics cards");
        PointerBuffer handles = stack.mallocPointer(count.get(0));
        check(vkEnumeratePhysicalDevices(instance, count, handles), "list graphics cards");
        VkPhysicalDevice fallback = null;
        int fallbackFamily = -1;
        for (int i = 0; i < count.get(0); i++) {
            VkPhysicalDevice candidate = new VkPhysicalDevice(handles.get(i), instance);
            int family = graphicsAndPresentFamily(candidate, stack);
            if (family < 0) continue;
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(candidate, props);
            if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
                gpu = candidate;
                queueFamily = family;
                gpuName = props.deviceNameString();
                return;
            }
            if (fallback == null) {
                fallback = candidate;
                fallbackFamily = family;
                gpuName = props.deviceNameString();
            }
        }
        if (fallback == null) throw new IllegalStateException("No graphics card can show Vulkan output in this window");
        gpu = fallback;
        queueFamily = fallbackFamily;
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
        VkDeviceCreateInfo info = VkDeviceCreateInfo.calloc(stack)
            .sType$Default()
            .pQueueCreateInfos(queues)
            .ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME)));
        PointerBuffer pDevice = stack.mallocPointer(1);
        check(vkCreateDevice(gpu, info, null, pDevice), "open the graphics card");
        device = new VkDevice(pDevice.get(0), gpu, info);
        PointerBuffer pQueue = stack.mallocPointer(1);
        vkGetDeviceQueue(device, queueFamily, 0, pQueue);
        queue = new VkQueue(pQueue.get(0), device);
    }

    private void createCommands(MemoryStack stack) {
        VkCommandPoolCreateInfo pool = VkCommandPoolCreateInfo.calloc(stack)
            .sType$Default()
            .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
            .queueFamilyIndex(queueFamily);
        LongBuffer pPool = stack.mallocLong(1);
        check(vkCreateCommandPool(device, pool, null, pPool), "create a command pool");
        commandPool = pPool.get(0);

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
        LongBuffer handle = stack.mallocLong(1);
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
            VkExtent2D extent = VkExtent2D.calloc(stack);
            if (caps.currentExtent()
                .width() != 0xFFFFFFFF) {
                extent.set(caps.currentExtent());
            } else {
                extent.width(
                    clamp(
                        w.get(0),
                        caps.minImageExtent()
                            .width(),
                        caps.maxImageExtent()
                            .width()))
                    .height(
                        clamp(
                            h.get(0),
                            caps.minImageExtent()
                                .height(),
                            caps.maxImageExtent()
                                .height()));
            }
            if (extent.width() == 0 || extent.height() == 0) {
                // Minimised: nothing to present until the window has a size again.
                width = height = 0;
                needsRebuild = true;
                return;
            }

            IntBuffer count = stack.mallocInt(1);
            vkGetPhysicalDeviceSurfaceFormatsKHR(gpu, surface, count, null);
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(gpu, surface, count, formats);
            int chosenFormat = formats.get(0)
                .format();
            int colorSpace = formats.get(0)
                .colorSpace();
            for (int i = 0; i < count.get(0); i++) {
                // Minecraft's colours are meant for a plain (not sRGB-converting) 8-bit target.
                if (formats.get(i)
                    .format() == VK_FORMAT_B8G8R8A8_UNORM) {
                    chosenFormat = VK_FORMAT_B8G8R8A8_UNORM;
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
                .imageFormat(chosenFormat)
                .imageColorSpace(colorSpace)
                .imageExtent(extent)
                .imageArrayLayers(1)
                .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
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
            images = new long[count.get(0)];
            for (int i = 0; i < images.length; i++) images[i] = pImages.get(i);

            for (long s : renderDone) vkDestroySemaphore(device, s, null);
            renderDone = new long[images.length];
            VkSemaphoreCreateInfo semaphore = VkSemaphoreCreateInfo.calloc(stack)
                .sType$Default();
            LongBuffer handle = stack.mallocLong(1);
            for (int i = 0; i < images.length; i++) {
                check(vkCreateSemaphore(device, semaphore, null, handle), "create a semaphore");
                renderDone[i] = handle.get(0);
            }

            format = chosenFormat;
            width = extent.width();
            height = extent.height();
            needsRebuild = false;
        }
    }

    /** No waiting for the screen: immediate if the driver offers it, then mailbox, then the always-present FIFO. */
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

    /** Shows one frame: the whole window cleared to the given colour. */
    synchronized void present(float r, float g, float b) {
        if (device == null) return;
        if (needsRebuild) {
            vkDeviceWaitIdle(device);
            createSwapchain();
            if (needsRebuild) return;
        }
        try (MemoryStack stack = stackPush()) {
            int f = frame;
            vkWaitForFences(device, frameDone[f], true, Long.MAX_VALUE);

            IntBuffer pIndex = stack.mallocInt(1);
            int acquired = vkAcquireNextImageKHR(
                device,
                swapchain,
                Long.MAX_VALUE,
                imageReady[f],
                VK_NULL_HANDLE,
                pIndex);
            if (acquired == VK_ERROR_OUT_OF_DATE_KHR) {
                needsRebuild = true;
                return;
            }
            if (acquired != VK_SUCCESS && acquired != VK_SUBOPTIMAL_KHR) check(acquired, "get a swapchain image");
            int index = pIndex.get(0);
            vkResetFences(device, frameDone[f]);

            VkCommandBuffer cmd = commands[f];
            vkResetCommandBuffer(cmd, 0);
            vkBeginCommandBuffer(
                cmd,
                VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
            VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .levelCount(1)
                .layerCount(1);
            barrier(
                cmd,
                stack,
                images[index],
                range,
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0,
                VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT);
            VkClearColorValue color = VkClearColorValue.calloc(stack);
            color.float32(0, r)
                .float32(1, g)
                .float32(2, b)
                .float32(3, 1f);
            vkCmdClearColorImage(cmd, images[index], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, color, range);
            barrier(
                cmd,
                stack,
                images[index],
                range,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_ACCESS_TRANSFER_WRITE_BIT,
                0,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            vkEndCommandBuffer(cmd);

            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                .sType$Default()
                .waitSemaphoreCount(1)
                .pWaitSemaphores(stack.longs(imageReady[f]))
                .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT))
                .pCommandBuffers(stack.pointers(cmd))
                .pSignalSemaphores(stack.longs(renderDone[index]));
            check(vkQueueSubmit(queue, submit, frameDone[f]), "submit a frame");

            VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack)
                .sType$Default()
                .pWaitSemaphores(stack.longs(renderDone[index]))
                .swapchainCount(1)
                .pSwapchains(stack.longs(swapchain))
                .pImageIndices(stack.ints(index));
            int presented = vkQueuePresentKHR(queue, present);
            if (presented == VK_ERROR_OUT_OF_DATE_KHR || presented == VK_SUBOPTIMAL_KHR) needsRebuild = true;
            else if (presented != VK_SUCCESS) check(presented, "present a frame");
            frame = (frame + 1) % FRAMES_IN_FLIGHT;
        }
    }

    /** The window was resized or changed mode; the swapchain is rebuilt before the next frame. */
    void windowChanged() {
        needsRebuild = true;
    }

    private static void barrier(VkCommandBuffer cmd, MemoryStack stack, long image, VkImageSubresourceRange range,
        int from, int to, int srcAccess, int dstAccess, int srcStage, int dstStage) {
        VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
        b.get(0)
            .sType$Default()
            .oldLayout(from)
            .newLayout(to)
            .srcAccessMask(srcAccess)
            .dstAccessMask(dstAccess)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .image(image)
            .subresourceRange(range);
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, b);
    }

    void destroy() {
        if (device != null) {
            vkDeviceWaitIdle(device);
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

    private static void check(int result, String what) {
        if (result != VK_SUCCESS)
            throw new IllegalStateException("Vulkan could not " + what + " (error " + result + ")");
    }
}
