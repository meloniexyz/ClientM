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
    /** The GPU has written into this buffer (a copy), so its CPU-visible memory may not hold the latest contents. */
    boolean gpuWritten;
    /** The last frame that recorded GPU work using this buffer. */
    long lastUse;

    /** True for memory on the graphics card that the CPU cannot see (no {@link #address}). */
    final boolean gpuOnly;

    VulkanBuffer(VulkanDevice dev, long size) {
        this(dev, size, false);
    }

    /** Bytes actually allocated (small CPU-visible buffers are rounded up to a size class and recycled). */
    final long capacity;

    // Recycled small CPU-visible buffers by size class (256 B << i): {buffer, memory, address}. Device lock held.
    private static final int CLASSES = 13; // up to 1 MB
    @SuppressWarnings("unchecked")
    private static final java.util.ArrayDeque<long[]>[] POOL = new java.util.ArrayDeque[CLASSES];
    static {
        for (int i = 0; i < CLASSES; i++) POOL[i] = new java.util.ArrayDeque<>();
    }

    private static int sizeClass(long size) {
        int c = 0;
        while (c < CLASSES && (256L << c) < size) c++;
        return c; // CLASSES means "too big to pool"
    }

    /** A buffer for the game's data: small CPU-visible ones come from the recycling pool when possible. */
    static VulkanBuffer obtain(VulkanDevice dev, long size, boolean gpuOnly) {
        size = Math.max(16, size);
        if (!gpuOnly) {
            int c = sizeClass(size);
            if (c < CLASSES) {
                long[] h = POOL[c].poll();
                if (h != null) return new VulkanBuffer(size, 256L << c, h);
                return new VulkanBuffer(dev, size, 256L << c, false);
            }
        }
        return new VulkanBuffer(dev, size, size, gpuOnly);
    }

    private VulkanBuffer(long size, long capacity, long[] handles) {
        this.size = size;
        this.capacity = capacity;
        this.gpuOnly = false;
        buffer = handles[0];
        memory = handles[1];
        address = handles[2];
    }

    VulkanBuffer(VulkanDevice dev, long size, boolean gpuOnly) {
        this(dev, size, Math.max(16, size), gpuOnly);
    }

    private VulkanBuffer(VulkanDevice dev, long size, long capacity, boolean gpuOnly) {
        this.size = Math.max(16, size);
        this.capacity = Math.max(this.size, capacity);
        this.gpuOnly = gpuOnly;
        if (Stats.ON) Stats.fBuffers++;
        VkDevice vk = dev.vk();
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                .sType$Default()
                .size(this.capacity)
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
                .memoryTypeIndex(
                    gpuOnly ? dev.memoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0)
                        : dev.memoryType(req.memoryTypeBits(), coherent, coherent));
            VulkanDevice.check(vkAllocateMemory(vk, alloc, null, handle), "allocate buffer memory");
            memory = handle.get(0);
            vkBindBufferMemory(vk, buffer, memory, 0);
            if (gpuOnly) return;
            PointerBuffer pData = stack.mallocPointer(1);
            VulkanDevice.check(vkMapMemory(vk, memory, 0, VK_WHOLE_SIZE, 0, pData), "map buffer memory");
            address = pData.get(0);
        }
    }

    ByteBuffer view(long offset, long length) {
        return MemoryUtil.memByteBuffer(address + offset, (int) Math.min(length, size - offset));
    }

    void release(VulkanDevice dev) {
        final long b = buffer, m = memory, a = address;
        buffer = memory = address = 0;
        int c = gpuOnly ? CLASSES : sizeClass(capacity);
        if (c < CLASSES && (256L << c) == capacity) {
            // Back to the pool once the GPU is done with it.
            dev.later(() -> {
                if (POOL[c].size() < 64) POOL[c].add(new long[] { b, m, a });
                else {
                    vkDestroyBuffer(dev.vk(), b, null);
                    vkFreeMemory(dev.vk(), m, null);
                }
            });
            return;
        }
        dev.later(() -> {
            VkDevice vk = dev.vk();
            vkDestroyBuffer(vk, b, null);
            vkFreeMemory(vk, m, null);
        });
    }
}
