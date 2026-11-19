package com.clientm.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

import java.nio.LongBuffer;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * A 2D image in graphics memory: a texture, a render target, or both. It is moved to the GENERAL layout as soon as it
 * exists and stays there, so it can be sampled, drawn into and copied without layout bookkeeping.
 */
@Lwjgl3Aware
final class VulkanImage {

    final int width, height, levels, format, aspect;
    final boolean depth;
    /** An upload into this image is queued and not recorded yet (see GLResources.flushUploads). */
    boolean pendingUpload;
    long image, memory, view;
    /** One view per mip level, for drawing into a single level. */
    long[] levelViews;
    /** For depth images: a view with depth and stencil, for drawing into. */
    long attachView;

    VulkanImage(VulkanDevice dev, int width, int height, int levels, int format, boolean depth) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.levels = Math.max(1, levels);
        this.format = format;
        this.depth = depth;
        this.aspect = depth ? (Formats.hasStencil(format) ? VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT
            : VK_IMAGE_ASPECT_DEPTH_BIT) : VK_IMAGE_ASPECT_COLOR_BIT;
        if (Stats.ON) Stats.fImages++;
        VkDevice vk = dev.vk();
        try (MemoryStack stack = stackPush()) {
            int usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                | (depth ? VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT : VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                .sType$Default()
                .imageType(VK_IMAGE_TYPE_2D)
                .format(format)
                .mipLevels(this.levels)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent()
                .set(this.width, this.height, 1);
            LongBuffer handle = stack.mallocLong(1);
            VulkanDevice.check(vkCreateImage(vk, info, null, handle), "create an image");
            image = handle.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(vk, image, req);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                .sType$Default()
                .allocationSize(req.size())
                .memoryTypeIndex(dev.memoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0));
            VulkanDevice.check(vkAllocateMemory(vk, alloc, null, handle), "allocate image memory");
            memory = handle.get(0);
            vkBindImageMemory(vk, image, memory, 0);

            view = createView(vk, stack, 0, this.levels);
            attachView = depth ? createView(vk, stack, 0, 1, aspect) : 0;
            levelViews = new long[this.levels];
            for (int i = 0; i < this.levels; i++) levelViews[i] = this.levels == 1 ? view : createView(vk, stack, i, 1);
        }
        Barriers.image(dev.cmd(), image, aspect, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
    }

    private long createView(VkDevice vk, MemoryStack stack, int baseLevel, int count) {
        return createView(vk, stack, baseLevel, count, depth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT);
    }

    private long createView(VkDevice vk, MemoryStack stack, int baseLevel, int count, int viewAspect) {
        VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
            .sType$Default()
            .image(image)
            .viewType(VK_IMAGE_VIEW_TYPE_2D)
            .format(format)
            .subresourceRange(
                r -> r.aspectMask(viewAspect)
                    .baseMipLevel(baseLevel)
                    .levelCount(count)
                    .layerCount(1));
        LongBuffer handle = stack.mallocLong(1);
        VulkanDevice.check(vkCreateImageView(vk, info, null, handle), "create an image view");
        return handle.get(0);
    }

    /** Frees the image once the graphics card can no longer be using it. */
    void release(VulkanDevice dev) {
        final long img = image, mem = memory, v = view, av = attachView;
        final long[] lv = levelViews;
        image = memory = view = 0;
        dev.later(() -> {
            VkDevice vk = dev.vk();
            for (long l : lv) if (l != v) vkDestroyImageView(vk, l, null);
            vkDestroyImageView(vk, v, null);
            if (av != 0) vkDestroyImageView(vk, av, null);
            vkDestroyImage(vk, img, null);
            vkFreeMemory(vk, mem, null);
        });
    }
}
