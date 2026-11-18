package com.clientm.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/** The two kinds of ordering used: "everything before finishes before anything after", and image layout changes. */
@Lwjgl3Aware
final class Barriers {

    private Barriers() {}

    /** All earlier GPU work, including its memory writes, is finished and visible before any later work starts. */
    static void full(VkCommandBuffer cmd) {
        try (MemoryStack stack = stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack);
            b.get(0)
                .sType$Default()
                .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            vkCmdPipelineBarrier(
                cmd,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0,
                b,
                null,
                null);
        }
    }

    static void image(VkCommandBuffer cmd, long image, int aspect, int from, int to) {
        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
            b.get(0)
                .sType$Default()
                .oldLayout(from)
                .newLayout(to)
                .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .subresourceRange(
                    r -> r.aspectMask(aspect)
                        .levelCount(VK_REMAINING_MIP_LEVELS)
                        .layerCount(VK_REMAINING_ARRAY_LAYERS));
            vkCmdPipelineBarrier(
                cmd,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0,
                null,
                null,
                b);
        }
    }
}
