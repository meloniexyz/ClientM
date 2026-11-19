package com.clientm.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * Development aid (with CLIENTM_VK_STATS): GPU timestamps between labelled points of each frame. The time from one
 * stamp to the next is added to the first stamp's label; averages per frame are logged every 2000 frames.
 */
@Lwjgl3Aware
final class GpuTimer {

    private static final Logger LOG = LogManager.getLogger("ClientM/Vulkan/GPU");
    private static final int MAX = 128;

    private final VulkanDevice dev;
    private final long[] pools;
    private final String[][] labels;
    private final int[] counts;
    private final double nsPerTick;
    private final Map<String, Double> totals = new LinkedHashMap<>();
    private long frames;

    GpuTimer(VulkanDevice dev, VkPhysicalDevice gpu, int slots) {
        this.dev = dev;
        pools = new long[slots];
        labels = new String[slots][MAX];
        counts = new int[slots];
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(gpu, props);
            nsPerTick = props.limits()
                .timestampPeriod();
            LongBuffer handle = stack.mallocLong(1);
            for (int i = 0; i < slots; i++) {
                VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .queryType(VK_QUERY_TYPE_TIMESTAMP)
                    .queryCount(MAX);
                VulkanDevice.check(vkCreateQueryPool(dev.vk(), info, null, handle), "create a query pool");
                pools[i] = handle.get(0);
            }
        }
    }

    /** At the start of a slot's frame, after its previous frame finished: collect that frame's times, start anew. */
    void begin(VkCommandBuffer cmd, int slot) {
        int n = counts[slot];
        if (n > 1) {
            try (MemoryStack stack = stackPush()) {
                LongBuffer t = stack.mallocLong(n);
                if (vkGetQueryPoolResults(dev.vk(), pools[slot], 0, n, t, 8, VK_QUERY_RESULT_64_BIT) == VK_SUCCESS) {
                    for (int i = 0; i + 1 < n; i++) {
                        double us = (t.get(i + 1) - t.get(i)) * nsPerTick / 1000.0;
                        totals.merge(labels[slot][i], us, Double::sum);
                    }
                    if (++frames % 2000 == 0) {
                        StringBuilder sb = new StringBuilder("GPU microseconds per frame:");
                        double sum = 0;
                        for (Map.Entry<String, Double> e : totals.entrySet()) {
                            sum += e.getValue() / frames;
                            sb.append(String.format(" [%s %.1f]", e.getKey(), e.getValue() / frames));
                        }
                        LOG.info("{} total {}", sb, String.format("%.1f", sum));
                        totals.clear();
                        frames = 0;
                    }
                }
            }
        }
        vkCmdResetQueryPool(cmd, pools[slot], 0, MAX);
        counts[slot] = 0;
    }

    void stamp(VkCommandBuffer cmd, int slot, String label) {
        int i = counts[slot];
        if (i >= MAX) return;
        labels[slot][i] = label;
        counts[slot] = i + 1;
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pools[slot], i);
    }
}
