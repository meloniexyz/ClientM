package com.clientm.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * A buffer in memory that both the CPU and the graphics card can reach, mapped for the whole of its life. The game
 * writes straight into it; OpenGL's rule that a write must not change what earlier draws see is kept by the backend
 * (it gives a buffer new memory, or copies on the GPU, when the old contents may still be in use).
 */
@Lwjgl3Aware
final class VulkanBuffer {

    static final int USAGE = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT
        | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
        | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
        | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
        | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
        | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

    final long size;
    long buffer, memory;
    /** Address of the mapped memory. */
    long address;
    /** The last frame that recorded GPU work using this buffer. */
    long lastUse;

    VulkanBuffer(VulkanDevice dev, long size) {
        this.size = Math.max(16, size);
        VkDevice vk = dev.vk();
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                .sType$Default()
                .size(this.size)
                .usage(USAGE)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer handle = stack.mallocLong(1);
            VulkanDevice.check(vkCreateBuffer(vk, info, null, handle), "create a buffer");
            buffer = handle.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(vk, buffer, req);
            int coherent = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                .sType$Default()
                .allocationSize(req.size())
                .memoryTypeIndex(dev.memoryType(req.memoryTypeBits(), coherent, coherent));
            VulkanDevice.check(vkAllocateMemory(vk, alloc, null, handle), "allocate buffer memory");
            memory = handle.get(0);
            vkBindBufferMemory(vk, buffer, memory, 0);
            PointerBuffer pData = stack.mallocPointer(1);
            VulkanDevice.check(vkMapMemory(vk, memory, 0, VK_WHOLE_SIZE, 0, pData), "map buffer memory");
            address = pData.get(0);
        }
    }

    ByteBuffer view(long offset, long length) {
        return MemoryUtil.memByteBuffer(address + offset, (int) Math.min(length, size - offset));
    }

    void release(VulkanDevice dev) {
        final long b = buffer, m = memory;
        buffer = memory = address = 0;
        dev.later(() -> {
            VkDevice vk = dev.vk();
            vkDestroyBuffer(vk, b, null);
            vkFreeMemory(vk, m, null);
        });
    }
}
