package com.clientm.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

import java.nio.ByteBuffer;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkClearDepthStencilValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageSubresourceRange;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * The OpenGL objects the game creates (buffers, textures, sync objects) and the game's screen image, kept as Vulkan
 * objects. Everything that records GPU work holds {@link #lock}, because Forge's loading screen draws from its own
 * thread while the game loads on the main thread.
 */
@Lwjgl3Aware
final class GLResources {

    /** One lock per device: every thread that records GPU work takes it. */
    final Object lock;
    final VulkanDevice dev;

    // ------------------------------------------------------------------ buffers

    static final class GLBuffer {

        final int id;
        VulkanBuffer vk;
        boolean persistent;

        GLBuffer(int id) {
            this.id = id;
        }
    }

    final IdMap<GLBuffer> buffers = new IdMap<>();

    // ------------------------------------------------------------------ textures

    static final class GLTexture {

        final int id;
        VulkanImage image;
        int internal = Formats.GL_RGBA8;
        int maxLevel = 1000;
        int minFilter = 0x2702, magFilter = 0x2601; // NEAREST_MIPMAP_LINEAR, LINEAR (OpenGL defaults)
        int wrapS = 0x2901, wrapT = 0x2901; // REPEAT
        float anisotropy = 1f, lodBias, minLod = -1000f, maxLod = 1000f;
        int compareMode, compareFunc = 0x0203;
        /** Bumped whenever sampling settings change, so cached samplers are rebuilt. */
        int samplerVersion;
        /** The Vulkan sampler for the current settings and image (see VulkanRenderer.sampler). */
        long vkSampler;
        int vkSamplerVersion = -1;
        VulkanImage vkSamplerImage;
        /** Sizes this texture has had, for diagnostics. */
        final StringBuilder history = new StringBuilder();

        GLTexture(int id) {
            this.id = id;
        }
    }

    final IdMap<GLTexture> textures = new IdMap<>();
    static final int UNITS = 32;

    private int misfits;

    /**
     * What OpenGL keeps per context: bindings, the active texture unit, pixel transfer settings and clear values.
     * Forge's loading screen draws on its own thread with its own context, so this is kept per thread.
     */
    static final class Ctx {

        /** Buffer bound to each OpenGL target (see {@link #slot}). */
        final int[] bound = new int[16];
        final int[] boundTextures = new int[UNITS];
        int activeUnit;
        int unpackRowLength, unpackSkipPixels, unpackSkipRows, unpackAlignment = 4;
        int drawFramebuffer, readFramebuffer;
        float clearR, clearG, clearB, clearA, clearDepth = 1f;
        int clearStencil;
        /** The program in use (glUseProgram). */
        int program;
        /** The vertex array in use (glBindVertexArray). */
        int vao;
        final RenderState state = new RenderState();
        /** Indexed uniform buffer bindings (glBindBufferRange / glBindBufferBase on GL_UNIFORM_BUFFER). */
        final int[] uboBuffer = new int[64];
        final long[] uboOffset = new long[64];
        final long[] uboSize = new long[64];
    }

    private final ThreadLocal<Ctx> context = ThreadLocal.withInitial(Ctx::new);

    Ctx ctx() {
        return context.get();
    }

    // ------------------------------------------------------------------ the game's screen image

    VulkanImage screenColor, screenDepth;

    // ------------------------------------------------------------------ staging memory for uploads

    /** The staging block the last {@link #stage} call wrote into. */
    private VulkanBuffer staging;
    private final VulkanBuffer[] stagingBlocks = new VulkanBuffer[VulkanDevice.FRAMES_IN_FLIGHT];
    private final long[] stagingUsed = new long[VulkanDevice.FRAMES_IN_FLIGHT];
    private final long[] stagingFrame = new long[VulkanDevice.FRAMES_IN_FLIGHT];

    GLResources(VulkanDevice dev) {
        this.dev = dev;
        this.lock = dev;
    }

    /** The OpenGL buffer targets the game uses, as small indices. */
    static int slot(int target) {
        switch (target) {
            case 0x8892:
                return 0; // ARRAY_BUFFER
            case 0x8893:
                return 1; // ELEMENT_ARRAY_BUFFER
            case 0x88EB:
                return 2; // PIXEL_PACK
            case 0x88EC:
                return 3; // PIXEL_UNPACK
            case 0x8A11:
                return 4; // UNIFORM
            case 0x8C2A:
                return 5; // TEXTURE_BUFFER
            case 0x8F36:
                return 6; // COPY_READ
            case 0x8F37:
                return 7; // COPY_WRITE
            case 0x8F3F:
                return 8; // DRAW_INDIRECT
            case 0x90D2:
                return 9; // SHADER_STORAGE
            case 0x90EE:
                return 10; // DISPATCH_INDIRECT
            case 0x80EE:
                return 11; // PARAMETER_BUFFER
            case 0x92C0:
                return 12; // ATOMIC_COUNTER
            case 0x8C8E:
                return 13; // TRANSFORM_FEEDBACK
            case 0x9192:
                return 14; // QUERY
            default:
                return 15;
        }
    }

    GLBuffer bufferAt(int target) {
        return buffers.get(ctx().bound[slot(target)]);
    }

    GLBuffer buffer(int id) {
        GLBuffer b = buffers.get(id);
        if (b == null && id != 0) {
            b = new GLBuffer(id);
            buffers.put(id, b);
        }
        return b;
    }

    /** True when the graphics card may still read this buffer's current memory. */
    boolean inUse(VulkanBuffer b) {
        return b != null && b.lastUse != 0 && !dev.isDone(b.lastUse);
    }

    /** Gives the buffer new memory of the given size (the old memory is freed once the GPU is done with it). */
    void allocate(GLBuffer b, long size, ByteBuffer data, boolean persistent) {
        allocate(b, size, data, persistent, false);
    }

    /**
     * @param gpuOnly the buffer is only written by uploads and GPU copies, never mapped: it lives in graphics card
     *                memory, which the GPU reads much faster than system memory. Mapping it later moves it back.
     */
    void allocate(GLBuffer b, long size, ByteBuffer data, boolean persistent, boolean gpuOnly) {
        if (b == null) return;
        synchronized (lock) {
            if (b.vk != null) b.vk.release(dev);
            // Small buffers (such as Celeritas' per-region draw command lists, re-specified often) stay CPU-visible:
            // filling them is a plain copy, with no GPU copy breaking up the frame's drawing.
            b.vk = VulkanBuffer.obtain(dev, Math.max(size, 16), gpuOnly && !persistent && size >= (256 << 10));
            b.persistent = persistent;
            if (data != null) {
                if (b.vk.gpuOnly) write(b, 0, data);
                else MemoryUtil.memCopy(MemoryUtil.memAddress(data), b.vk.address, Math.min(size, data.remaining()));
            }
        }
    }

    /** Moves a graphics-card-only buffer to CPU-visible memory, with its contents (waits for the GPU). */
    void toHost(GLBuffer b) {
        if (b == null || b.vk == null || !b.vk.gpuOnly) return;
        synchronized (lock) {
            VulkanBuffer old = b.vk;
            VulkanBuffer host = new VulkanBuffer(dev, old.size);
            VkCommandBuffer cmd = dev.cmd();
            Barriers.full(cmd);
            try (MemoryStack stack = stackPush()) {
                VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
                region.get(0)
                    .size(old.size);
                vkCmdCopyBuffer(cmd, old.buffer, host.buffer, region);
            }
            Barriers.full(cmd);
            dev.flushAndWait();
            old.release(dev);
            b.vk = host;
            host.lastUse = 0;
        }
    }

    /** OpenGL's glBufferSubData: later draws see the new data, earlier ones the old. */
    void write(GLBuffer b, long offset, ByteBuffer data) {
        if (b == null || b.vk == null || data == null) return;
        long n = Math.min(data.remaining(), b.vk.size - offset);
        if (n <= 0) return;
        synchronized (lock) {
            if (!b.vk.gpuOnly && !inUse(b.vk) && b.vk.lastUse != dev.currentFrame()) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(data), b.vk.address + offset, n);
                return;
            }
            if (!b.vk.gpuOnly && !b.vk.gpuWritten && b.vk.size <= (1 << 20)) {
                // Small CPU-visible buffer still in use: give it fresh memory holding the old contents plus the new
                // data. Draws already recorded keep reading the old memory, which is recycled once they are done.
                VulkanBuffer old = b.vk;
                VulkanBuffer fresh = VulkanBuffer.obtain(dev, old.size, false);
                MemoryUtil.memCopy(old.address, fresh.address, old.size);
                MemoryUtil.memCopy(MemoryUtil.memAddress(data), fresh.address + offset, n);
                old.release(dev);
                b.vk = fresh;
                return;
            }
            // Still in use: the new data goes in through a copy on the GPU, in order with the frame's work.
            if (Stats.ON) {
                Stats.stagedWrites++;
                Stats.fStaged++;
            }
            long src = stage(MemoryUtil.memAddress(data), n);
            VkCommandBuffer cmd = dev.cmd();
            Barriers.full(cmd);
            try (MemoryStack stack = stackPush()) {
                VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
                region.get(0)
                    .srcOffset(src)
                    .dstOffset(offset)
                    .size(n);
                vkCmdCopyBuffer(cmd, staging.buffer, b.vk.buffer, region);
            }
            Barriers.full(cmd);
            b.vk.lastUse = dev.currentFrame();
            b.vk.gpuWritten = true;
        }
    }

    void copy(GLBuffer from, GLBuffer to, long readOffset, long writeOffset, long size) {
        if (from == null || to == null || from.vk == null || to.vk == null || size <= 0) return;
        if (Stats.ON) Stats.bufferCopies++;
        synchronized (lock) {
            VkCommandBuffer cmd = dev.cmd();
            Barriers.full(cmd);
            try (MemoryStack stack = stackPush()) {
                VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
                region.get(0)
                    .srcOffset(readOffset)
                    .dstOffset(writeOffset)
                    .size(size);
                vkCmdCopyBuffer(cmd, from.vk.buffer, to.vk.buffer, region);
            }
            Barriers.full(cmd);
            from.vk.lastUse = to.vk.lastUse = dev.currentFrame();
            to.vk.gpuWritten = true;
        }
    }

    /**
     * Copies bytes into this frame's staging memory and returns their offset in {@link #staging}. Each frame slot has
     * its own block, reused only after that slot's previous frame has finished on the GPU.
     */
    long stage(long address, long n) {
        dev.cmd(); // starts the frame, which waits for this slot's previous frame
        long aligned = (n + 255) & ~255L;
        long frame = dev.currentFrame();
        int s = (int) (frame % VulkanDevice.FRAMES_IN_FLIGHT);
        if (stagingFrame[s] != frame) {
            stagingFrame[s] = frame;
            stagingUsed[s] = 0;
        }
        VulkanBuffer buf = stagingBlocks[s];
        if (buf == null || stagingUsed[s] + aligned > buf.size) {
            // Commands of this frame may still point into the old block; it is freed once the frame is done.
            if (buf != null) buf.release(dev);
            buf = new VulkanBuffer(dev, Math.max(64L << 20, aligned * 2));
            stagingBlocks[s] = buf;
            stagingUsed[s] = 0;
        }
        long at = stagingUsed[s];
        MemoryUtil.memCopy(address, buf.address + at, n);
        stagingUsed[s] += aligned;
        staging = buf;
        return at;
    }

    // ------------------------------------------------------------------ textures

    GLTexture texture(int id) {
        GLTexture t = textures.get(id);
        if (t == null && id != 0) {
            t = new GLTexture(id);
            textures.put(id, t);
        }
        return t;
    }

    GLTexture boundTexture() {
        return textures.get(ctx().boundTextures[ctx().activeUnit]);
    }

    /** Makes sure the texture has an image of this size and format, with room for its mip levels. */
    void define(GLTexture t, int internal, int width, int height, int levels) {
        int format = Formats.vulkan(internal, dev.depthFormat());
        boolean depth = Formats.isDepth(internal);
        VulkanImage img = t.image;
        if (img != null && img.width == width && img.height == height && img.format == format && img.levels >= levels)
            return;
        synchronized (lock) {
            if (img != null) img.release(dev);
            long t0 = Stats.ON ? System.nanoTime() : 0;
            t.image = new VulkanImage(dev, width, height, levels, format, depth);
            if (Stats.ON) {
                Stats.fImageNs += System.nanoTime() - t0;
                Stats.fWhat += " image(texture " + t.id + " " + width + "x" + height + "/" + levels + ")";
            }
            t.internal = internal;
            if (t.history.length() < 300) t.history.append(width)
                .append('x')
                .append(height)
                .append('/')
                .append(levels)
                .append(' ');
        }
    }

    static int fullChain(int width, int height, int maxLevel) {
        int levels = 32 - Integer.numberOfLeadingZeros(Math.max(1, Math.max(width, height)));
        return Math.max(1, Math.min(levels, maxLevel + 1));
    }

    /**
     * Uploads pixels to one level of a texture. The game hands pixels in OpenGL's layouts; they are turned into the
     * image's byte order while staging.
     */
    // ------------------------------------------------------------------ queued texture uploads

    /** {staging buffer, offset, image, level, x, y, width, height} for each upload not recorded yet. */
    private final java.util.ArrayList<long[]> pending = new java.util.ArrayList<>();
    private final java.util.ArrayList<VulkanImage> pendingImages = new java.util.ArrayList<>();
    private boolean flushing;

    /** At shutdown: releases every buffer and image (freed by the device before it is destroyed). */
    void freeAll() {
        synchronized (lock) {
            for (GLBuffer b : buffers.values()) {
                if (b.vk != null) b.vk.release(dev);
                b.vk = null;
            }
            for (GLTexture t : textures.values()) {
                if (t.image != null) t.image.release(dev);
                t.image = null;
            }
            for (int i = 0; i < stagingBlocks.length; i++) {
                if (stagingBlocks[i] != null) stagingBlocks[i].release(dev);
                stagingBlocks[i] = null;
            }
            if (screenColor != null) screenColor.release(dev);
            if (screenDepth != null) screenDepth.release(dev);
            screenColor = screenDepth = null;
        }
    }

    boolean hasPendingUploads() {
        return !pending.isEmpty();
    }

    /** Records all queued uploads, between two barriers. Runs before any other recorded transfer (see Barriers). */
    void flushUploads() {
        if (pending.isEmpty() || flushing) return;
        flushing = true;
        try {
            VkCommandBuffer cmd = dev.cmd();
            Barriers.full(cmd);
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                for (long[] u : pending) {
                    final int level = (int) u[3];
                    region.get(0)
                        .bufferOffset(u[1])
                        .imageSubresource(
                            s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                .mipLevel(level)
                                .layerCount(1));
                    region.get(0)
                        .imageOffset()
                        .set((int) u[4], (int) u[5], 0);
                    region.get(0)
                        .imageExtent()
                        .set((int) u[6], (int) u[7], 1);
                    vkCmdCopyBufferToImage(cmd, u[0], u[2], VK_IMAGE_LAYOUT_GENERAL, region);
                }
            }
            Barriers.full(cmd);
            if (Stats.ON) Stats.fUploadFlushes++;
        } finally {
            pending.clear();
            for (VulkanImage img : pendingImages) img.pendingUpload = false;
            pendingImages.clear();
            flushing = false;
        }
    }

    void upload(GLTexture t, int level, int x, int y, int width, int height, int glFormat, int glType, long pixels) {
        if (Stats.ON) {
            Stats.uploads++;
            Stats.fUploads++;
        }
        VulkanImage img = t.image;
        if (img == null || pixels == 0 || width <= 0 || height <= 0 || img.depth) return;
        int lw = Math.max(1, img.width >> level), lh = Math.max(1, img.height >> level);
        if (level >= img.levels || x < 0 || y < 0 || x + width > lw || y + height > lh) {
            // Never record an upload that does not fit: the GPU would reject the whole frame.
            if (misfits++ < 8) {
                org.apache.logging.log4j.LogManager.getLogger("ClientM/Vulkan")
                    .warn(
                        "Skipped an upload that does not fit: texture {} is {}x{} ({} levels), upload {}x{} at {},{} level {}; unit {} history {}",
                        t.id,
                        img.width,
                        img.height,
                        img.levels,
                        width,
                        height,
                        x,
                        y,
                        level,
                        ctx().activeUnit,
                        t.history);
            }
            return;
        }
        int texel = Formats.texelSize(img.format);
        int srcTexel = sourceTexelSize(glFormat, glType);
        int rowLength = ctx().unpackRowLength > 0 ? ctx().unpackRowLength : width;
        long srcRow = align((long) rowLength * srcTexel, ctx().unpackAlignment);
        long src = pixels + ctx().unpackSkipRows * srcRow + (long) ctx().unpackSkipPixels * srcTexel;
        long n = (long) width * height * texel;
        synchronized (lock) {
            ByteBuffer tmp = MemoryUtil.memAlloc((int) n);
            try {
                long dst = MemoryUtil.memAddress(tmp);
                for (int row = 0; row < height; row++) {
                    convertRow(
                        src + row * srcRow,
                        dst + (long) row * width * texel,
                        width,
                        glFormat,
                        glType,
                        img.format);
                }
                long at = stage(dst, n);
                // Queued: uploads are recorded together the next time drawing stops (or before a draw that
                // samples this image), instead of each one stopping the drawing in progress.
                pending.add(new long[] { staging.buffer, at, img.image, level, x, y, width, height });
                img.pendingUpload = true;
                pendingImages.add(img);
            } finally {
                MemoryUtil.memFree(tmp);
            }
        }
    }

    private static long align(long v, int a) {
        return a <= 1 ? v : (v + a - 1) / a * a;
    }

    private static int sourceTexelSize(int glFormat, int glType) {
        int comps;
        switch (glFormat) {
            case Formats.GL_RED:
            case Formats.GL_ALPHA:
            case Formats.GL_LUMINANCE:
                comps = 1;
                break;
            case Formats.GL_RG:
                comps = 2;
                break;
            case Formats.GL_RGB:
                comps = 3;
                break;
            default:
                comps = 4;
        }
        switch (glType) {
            case Formats.GL_UNSIGNED_INT_8_8_8_8:
            case Formats.GL_UNSIGNED_INT_8_8_8_8_REV:
                return 4;
            case Formats.GL_FLOAT:
                return comps * 4;
            case Formats.GL_HALF_FLOAT:
            case Formats.GL_UNSIGNED_SHORT:
                return comps * 2;
            default:
                return comps;
        }
    }

    /** One row of pixels from the game's layout to the image's. */
    private static void convertRow(long src, long dst, int width, int glFormat, int glType, int vkFormat) {
        if (vkFormat != VK_FORMAT_R8G8B8A8_UNORM && vkFormat != VK_FORMAT_R8G8B8A8_SRGB) {
            // Other formats are uploaded as given (the game supplies them in matching layouts).
            MemoryUtil.memCopy(src, dst, (long) width * Formats.texelSize(vkFormat));
            return;
        }
        boolean bgra = glFormat == Formats.GL_BGRA;
        boolean reversed = glType == Formats.GL_UNSIGNED_INT_8_8_8_8;
        if (glFormat == Formats.GL_RGBA && !reversed) {
            MemoryUtil.memCopy(src, dst, width * 4L);
            return;
        }
        for (int i = 0; i < width; i++) {
            int r, g, b, a;
            if (glFormat == Formats.GL_RGB) {
                r = MemoryUtil.memGetByte(src + i * 3L);
                g = MemoryUtil.memGetByte(src + i * 3L + 1);
                b = MemoryUtil.memGetByte(src + i * 3L + 2);
                a = -1;
            } else if (glFormat == Formats.GL_RED || glFormat == Formats.GL_LUMINANCE) {
                r = g = b = MemoryUtil.memGetByte(src + i);
                a = -1;
            } else if (glFormat == Formats.GL_ALPHA) {
                r = g = b = -1;
                a = MemoryUtil.memGetByte(src + i);
            } else {
                int p = MemoryUtil.memGetInt(src + i * 4L);
                if (reversed) p = Integer.reverseBytes(p);
                int c0 = p & 0xFF, c1 = (p >>> 8) & 0xFF, c2 = (p >>> 16) & 0xFF, c3 = p >>> 24;
                if (bgra) {
                    b = c0;
                    g = c1;
                    r = c2;
                } else {
                    r = c0;
                    g = c1;
                    b = c2;
                }
                a = c3;
            }
            MemoryUtil.memPutInt(dst + i * 4L, (r & 0xFF) | (g & 0xFF) << 8 | (b & 0xFF) << 16 | (a & 0xFF) << 24);
        }
    }

    /** Fills the lower mip levels of a texture from level 0, like glGenerateMipmap. */
    void generateMipmaps(GLTexture t) {
        VulkanImage img = t.image;
        if (img == null || img.levels <= 1 || img.depth) return;
        synchronized (lock) {
            VkCommandBuffer cmd = dev.cmd();
            try (MemoryStack stack = stackPush()) {
                for (int level = 1; level < img.levels; level++) {
                    Barriers.full(cmd);
                    final int src = level - 1, dst = level;
                    VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                    blit.get(0)
                        .srcSubresource(
                            s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                .mipLevel(src)
                                .layerCount(1))
                        .dstSubresource(
                            s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                .mipLevel(dst)
                                .layerCount(1));
                    blit.get(0)
                        .srcOffsets(1)
                        .set(Math.max(1, img.width >> src), Math.max(1, img.height >> src), 1);
                    blit.get(0)
                        .dstOffsets(1)
                        .set(Math.max(1, img.width >> dst), Math.max(1, img.height >> dst), 1);
                    vkCmdBlitImage(
                        cmd,
                        img.image,
                        VK_IMAGE_LAYOUT_GENERAL,
                        img.image,
                        VK_IMAGE_LAYOUT_GENERAL,
                        blit,
                        VK_FILTER_LINEAR);
                }
                Barriers.full(cmd);
            }
        }
    }

    // ------------------------------------------------------------------ the game's screen image

    /** Keeps the screen image the size of the window. */
    void ensureScreen() {
        int[] size = dev.windowPixels();
        if (screenColor != null && screenColor.width == size[0] && screenColor.height == size[1]) return;
        synchronized (lock) {
            if (screenColor != null) screenColor.release(dev);
            if (screenDepth != null) screenDepth.release(dev);
            screenColor = new VulkanImage(dev, size[0], size[1], 1, VK_FORMAT_R8G8B8A8_UNORM, false);
            screenDepth = new VulkanImage(dev, size[0], size[1], 1, dev.depthFormat(), true);
        }
    }

    /** Clears whole images (used when nothing is being drawn into them at the moment). */
    void clearImages(VulkanImage color, VulkanImage depth, boolean clearColor, float[] rgba, boolean clearDepth,
        float depthValue, boolean clearStencil, int stencil) {
        synchronized (lock) {
            VkCommandBuffer cmd = dev.cmd();
            Barriers.full(cmd);
            try (MemoryStack stack = stackPush()) {
                if (clearColor && color != null) {
                    VkClearColorValue value = VkClearColorValue.calloc(stack);
                    value.float32(0, rgba[0])
                        .float32(1, rgba[1])
                        .float32(2, rgba[2])
                        .float32(3, rgba[3]);
                    VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .levelCount(1)
                        .layerCount(1);
                    vkCmdClearColorImage(cmd, color.image, VK_IMAGE_LAYOUT_GENERAL, value, range);
                }
                if ((clearDepth || clearStencil) && depth != null) {
                    VkClearDepthStencilValue value = VkClearDepthStencilValue.calloc(stack)
                        .depth(depthValue)
                        .stencil(stencil);
                    int aspect = (clearDepth ? VK_IMAGE_ASPECT_DEPTH_BIT : 0)
                        | (clearStencil && Formats.hasStencil(depth.format) ? VK_IMAGE_ASPECT_STENCIL_BIT : 0);
                    if (aspect != 0) {
                        VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                            .aspectMask(aspect)
                            .levelCount(1)
                            .layerCount(1);
                        vkCmdClearDepthStencilImage(cmd, depth.image, VK_IMAGE_LAYOUT_GENERAL, value, range);
                    }
                }
            }
            Barriers.full(cmd);
        }
    }
}
