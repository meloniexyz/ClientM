package com.clientm.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.*;

import java.nio.LongBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkInstance;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * Development aid: with CLIENTM_VK_VALIDATE set (and the Vulkan SDK installed), Khronos' validation layer checks every
 * Vulkan call and its findings are logged, each error with the Java code path that made the call. Each distinct
 * message is logged at most three times.
 */
@Lwjgl3Aware
final class Validation {

    private static final Logger LOG = LogManager.getLogger("ClientM/Vulkan/Validation");
    private static final Map<Integer, AtomicInteger> SEEN = new ConcurrentHashMap<>();
    private static VkDebugUtilsMessengerCallbackEXT callback;

    private Validation() {}

    static boolean requested() {
        return System.getenv("CLIENTM_VK_VALIDATE") != null || Boolean.getBoolean("clientm.vulkan.validate");
    }

    static void attach(VkInstance instance) {
        callback = VkDebugUtilsMessengerCallbackEXT.create((severity, types, data, user) -> {
            VkDebugUtilsMessengerCallbackDataEXT d = VkDebugUtilsMessengerCallbackDataEXT.create(data);
            int id = d.messageIdNumber();
            int n = SEEN.computeIfAbsent(id, k -> new AtomicInteger())
                .incrementAndGet();
            if (n <= 3) {
                String text = d.pMessageString();
                if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
                    LOG.error("Vulkan error: {}", text, new Throwable("called from"));
                } else {
                    LOG.warn("Vulkan warning: {}", text);
                }
            }
            return VK10.VK_FALSE;
        });
        try (MemoryStack stack = stackPush()) {
            VkDebugUtilsMessengerCreateInfoEXT info = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                .sType$Default()
                .messageSeverity(
                    VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                .messageType(
                    VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                        | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                .pfnUserCallback(callback);
            LongBuffer handle = stack.mallocLong(1);
            vkCreateDebugUtilsMessengerEXT(instance, info, null, handle);
        }
        LOG.info("Vulkan validation is on");
    }
}
