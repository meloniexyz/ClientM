package com.clientm.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VkClearAttachment;
import org.lwjgl.vulkan.VkClearRect;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * Vertex arrays, framebuffers, pipelines and draw calls.
 *
 * Drawing happens inside Vulkan "dynamic rendering" into the attachments of the bound draw framebuffer (or the game's
 * screen image for framebuffer 0). Rendering is begun lazily at the first draw or clear and ended when the target
 * changes or when anything outside rendering (copies, uploads, presenting) has to be recorded.
 *
 * Most OpenGL state is set as Vulkan dynamic state on each draw. A pipeline is only built for what cannot be
 * dynamic: the program, the vertex formats, blending and colour writes, the polygon mode, the primitive class and the
 * attachment formats. Pipelines are cached by those.
 *
 * Coordinates: everything is drawn with OpenGL's own row order (row 0 at the bottom); the image is flipped once when
 * it is shown. That keeps viewports, scissors, gl_FragCoord and render-to-texture identical to OpenGL; only the
 * winding of front faces is reversed, which {@link #frontFace} accounts for.
 */
@Lwjgl3Aware
final class VulkanRenderer {

    private static final Logger LOG = LogManager.getLogger("ClientM/Vulkan");

    // ------------------------------------------------------------------ objects

    static final class VertexArray {

        final boolean[] enabled = new boolean[16];
        final int[] buffer = new int[16], size = new int[16], type = new int[16], stride = new int[16],
            divisor = new int[16];
        final long[] offset = new long[16];
        final boolean[] normalized = new boolean[16], integer = new boolean[16];
        int elementBuffer;
        /** Separate-format attributes (glVertexAttribFormat/Binding); -1 for glVertexAttribPointer ones. */
        final int[] binding = new int[16], relativeOffset = new int[16];
        final int[] bindingBuffer = new int[16], bindingStride = new int[16];
        final long[] bindingOffset = new long[16];

        VertexArray() {
            Arrays.fill(binding, -1);
        }

        /** Works out the buffer, offset and stride of separate-format attributes from their binding points. */
        void resolve() {
            for (int i = 0; i < 16; i++) {
                int b = binding[i];
                if (b < 0 || b >= 16) continue;
                buffer[i] = bindingBuffer[b];
                offset[i] = bindingOffset[b] + relativeOffset[i];
                stride[i] = bindingStride[b];
            }
        }
    }

    static final class Framebuffer {

        final int[] colorTexture = new int[8], colorLevel = new int[8], colorRenderbuffer = new int[8];
        int depthTexture, depthRenderbuffer;
        int[] drawBuffers = { 0 };
        int readBuffer;
    }

    static final class Renderbuffer {

        VulkanImage image;
        int internal;
    }

    final Map<Integer, VertexArray> vaos = new HashMap<>();
    final Map<Integer, Framebuffer> framebuffers = new HashMap<>();
    final Map<Integer, Renderbuffer> renderbuffers = new HashMap<>();

    private final VulkanDevice dev;
    private final GLResources res;
    private final VulkanShaders shaders;
    private final Map<PipelineKey, Long> pipelines = new HashMap<>();
    private final Map<Long, Long> samplers = new HashMap<>();
    private GLResources.GLTexture blank;

    // per-frame ring for value blocks and constant attributes
    private final VulkanBuffer[] rings = new VulkanBuffer[VulkanDevice.FRAMES_IN_FLIGHT];
    private final long[] ringUsed = new long[VulkanDevice.FRAMES_IN_FLIGHT];
    private final long[] ringFrame = new long[VulkanDevice.FRAMES_IN_FLIGHT];
    private VulkanBuffer ring;

    // the rendering in progress
    private boolean rendering;
    private long targetKey;
    private int targetWidth, targetHeight;
    private int[] targetColorFormats = new int[0];
    private int targetDepthFormat;
    private boolean targetHasStencil;

    VulkanRenderer(VulkanDevice dev, GLResources res, VulkanShaders shaders) {
        this.dev = dev;
        this.res = res;
        this.shaders = shaders;
    }

    // ------------------------------------------------------------------ rendering scope

    /** Ends the current rendering, so copies and barriers can be recorded. */
    void endRendering() {
        if (rendering) {
            vkCmdEndRendering(dev.cmd());
            if (Stats.ON) dev.stamp("between passes");
            rendering = false;
            targetKey = 0;
        }
    }

    private static final class Target {

        VulkanImage[] colors = new VulkanImage[0];
        int[] colorLevels = new int[0];
        VulkanImage depth;
        long key;
        int width, height;
    }

    private Target target(int framebuffer) {
        Target t = new Target();
        if (framebuffer == 0) {
            res.ensureScreen();
            t.colors = new VulkanImage[] { res.screenColor };
            t.colorLevels = new int[] { 0 };
            t.depth = res.screenDepth;
        } else {
            Framebuffer fb = framebuffers.get(framebuffer);
            if (fb == null) return null;
            int n = 0;
            VulkanImage[] colors = new VulkanImage[fb.drawBuffers.length];
            int[] levels = new int[fb.drawBuffers.length];
            for (int db : fb.drawBuffers) {
                if (db < 0 || db >= 8) continue;
                VulkanImage img = colorImage(fb, db);
                if (img == null) continue;
                colors[n] = img;
                levels[n] = fb.colorLevel[db];
                n++;
            }
            t.colors = Arrays.copyOf(colors, n);
            t.colorLevels = Arrays.copyOf(levels, n);
            t.depth = depthImage(fb);
        }
        long key = 17;
        int w = Integer.MAX_VALUE, h = Integer.MAX_VALUE;
        for (int i = 0; i < t.colors.length; i++) {
            key = key * 31 + t.colors[i].image * 7 + t.colorLevels[i];
            w = Math.min(w, Math.max(1, t.colors[i].width >> t.colorLevels[i]));
            h = Math.min(h, Math.max(1, t.colors[i].height >> t.colorLevels[i]));
        }
        if (t.depth != null) {
            key = key * 31 + t.depth.image;
            w = Math.min(w, t.depth.width);
            h = Math.min(h, t.depth.height);
        }
        if (w == Integer.MAX_VALUE) return null;
        t.key = key == 0 ? 1 : key;
        t.width = w;
        t.height = h;
        return t;
    }

    VulkanImage colorImage(Framebuffer fb, int i) {
        if (fb.colorTexture[i] != 0) {
            GLResources.GLTexture tex = res.textures.get(fb.colorTexture[i]);
            return tex == null ? null : tex.image;
        }
        if (fb.colorRenderbuffer[i] != 0) {
            Renderbuffer rb = renderbuffers.get(fb.colorRenderbuffer[i]);
            return rb == null ? null : rb.image;
        }
        return null;
    }

    VulkanImage depthImage(Framebuffer fb) {
        if (fb.depthTexture != 0) {
            GLResources.GLTexture tex = res.textures.get(fb.depthTexture);
            return tex == null ? null : tex.image;
        }
        if (fb.depthRenderbuffer != 0) {
            Renderbuffer rb = renderbuffers.get(fb.depthRenderbuffer);
            return rb == null ? null : rb.image;
        }
        return null;
    }

    /** Makes sure rendering into the draw framebuffer is in progress; false when it has nothing to draw into. */
    private boolean beginRendering(int framebuffer) {
        Target t = target(framebuffer);
        if (t == null) return false;
        if (rendering && t.key == targetKey) return true;
        endRendering();
        if (Stats.ON) Stats.begins++;
        VkCommandBuffer cmd = dev.cmd();
        Barriers.full(cmd);
        try (MemoryStack stack = stackPush()) {
            VkRenderingAttachmentInfo.Buffer colors = VkRenderingAttachmentInfo.calloc(t.colors.length, stack);
            for (int i = 0; i < t.colors.length; i++) {
                colors.get(i)
                    .sType$Default()
                    .imageView(t.colors[i].levelViews[t.colorLevels[i]])
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            }
            VkRenderingInfo info = VkRenderingInfo.calloc(stack)
                .sType$Default()
                .layerCount(1)
                .pColorAttachments(colors);
            info.renderArea()
                .extent()
                .set(t.width, t.height);
            if (t.depth != null) {
                VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack)
                    .sType$Default()
                    .imageView(t.depth.attachView)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                info.pDepthAttachment(depth);
                if (Formats.hasStencil(t.depth.format)) info.pStencilAttachment(depth);
            }
            if (Stats.ON) dev.stamp("pass begin barrier");
            vkCmdBeginRendering(cmd, info);
            if (Stats.ON) dev.stamp(
                framebuffer == 0 ? "pass screen"
                    : "pass fb " + t.width + "x" + t.height + (t.depth != null ? "+depth" : ""));
        }
        rendering = true;
        targetKey = t.key;
        targetWidth = t.width;
        targetHeight = t.height;
        targetColorFormats = new int[t.colors.length];
        for (int i = 0; i < t.colors.length; i++) targetColorFormats[i] = t.colors[i].format;
        targetDepthFormat = t.depth == null ? VK_FORMAT_UNDEFINED : t.depth.format;
        targetHasStencil = t.depth != null && Formats.hasStencil(t.depth.format);
        return true;
    }

    // ------------------------------------------------------------------ clears

    /** glClear on a framebuffer: cleared inside rendering, limited to the scissor box like OpenGL. */
    void clear(GLResources.Ctx c, int mask) {
        synchronized (res.lock) {
            if (Stats.ON) Stats.clears++;
            clearLocked(c, mask);
        }
    }

    private void clearLocked(GLResources.Ctx c, int mask) {
        int fb = c.drawFramebuffer;
        if (!beginRendering(fb)) return;
        RenderState s = c.state;
        boolean color = (mask & 0x4000) != 0 && targetColorFormats.length > 0;
        boolean depth = (mask & 0x0100) != 0 && targetDepthFormat != VK_FORMAT_UNDEFINED && s.depthMask;
        boolean stencil = (mask & 0x0400) != 0 && targetHasStencil;
        if (!color && !depth && !stencil) return;
        try (MemoryStack stack = stackPush()) {
            int n = (color ? targetColorFormats.length : 0) + (depth || stencil ? 1 : 0);
            VkClearAttachment.Buffer atts = VkClearAttachment.calloc(n, stack);
            int i = 0;
            if (color) {
                for (int a = 0; a < targetColorFormats.length; a++, i++) {
                    atts.get(i)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .colorAttachment(a);
                    atts.get(i)
                        .clearValue()
                        .color()
                        .float32(0, c.clearR)
                        .float32(1, c.clearG)
                        .float32(2, c.clearB)
                        .float32(3, c.clearA);
                }
            }
            if (depth || stencil) {
                atts.get(i)
                    .aspectMask((depth ? VK_IMAGE_ASPECT_DEPTH_BIT : 0) | (stencil ? VK_IMAGE_ASPECT_STENCIL_BIT : 0));
                atts.get(i)
                    .clearValue()
                    .depthStencil()
                    .depth(c.clearDepth)
                    .stencil(c.clearStencil);
            }
            VkClearRect.Buffer rect = VkClearRect.calloc(1, stack);
            rect.get(0)
                .layerCount(1);
            int x = 0, y = 0, w = targetWidth, h = targetHeight;
            if (s.scissorTest) {
                x = clamp(s.scissor[0], 0, targetWidth);
                y = clamp(s.scissor[1], 0, targetHeight);
                w = clamp(s.scissor[2], 0, targetWidth - x);
                h = clamp(s.scissor[3], 0, targetHeight - y);
            }
            if (w <= 0 || h <= 0) return;
            rect.get(0)
                .rect()
                .offset()
                .set(x, y);
            rect.get(0)
                .rect()
                .extent()
                .set(w, h);
            vkCmdClearAttachments(dev.cmd(), atts, rect);
        }
    }

    // ------------------------------------------------------------------ draws

    static final int DRAW_ARRAYS = 0, DRAW_ELEMENTS = 1, DRAW_INDIRECT = 2;

    /**
     * One draw of any kind. For elements, {@code first} is the byte offset into the element buffer; for indirect
     * draws, the byte offset into the indirect buffer and {@code count} the number of draws.
     */
    void draw(GLResources.Ctx c, int kind, int mode, int first, int count, int indexType, int instances, int baseVertex,
        int stride) {
        VulkanShaders.GLProgram p = shaders.programs.get(c.program);
        if (p == null || !p.linked || count <= 0) return;
        VertexArray vao = vaos.get(c.vao);
        if (vao == null) return;
        synchronized (res.lock) {
            if (Stats.ON) Stats.draws++;
            blank(); // created before rendering starts: creating an image ends rendering
            if (!beginRendering(c.drawFramebuffer)) return;
            if (Stats.ON) dev.stamp(
                "p" + p.id
                    + (kind == DRAW_INDIRECT ? " indirect" : kind == DRAW_ELEMENTS ? " elements" : " arrays")
                    + (c.state.blend ? " blend" : ""));
            VkCommandBuffer cmd = dev.cmd();
            RenderState s = c.state;
            long pipeline = pipeline(p, vao, s, mode);
            if (pipeline == 0) return;
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            dynamicState(cmd, s, mode);
            if (!bindVertices(cmd, p, vao, s)) return;
            descriptors(cmd, c, p);
            long frame = dev.currentFrame();
            switch (kind) {
                case DRAW_ARRAYS:
                    vkCmdDraw(cmd, count, Math.max(1, instances), first, 0);
                    break;
                case DRAW_ELEMENTS:
                case DRAW_INDIRECT: {
                    GLResources.GLBuffer eb = res.buffers.get(vao.elementBuffer);
                    if (eb == null || eb.vk == null) return;
                    int vkType = indexType == 0x1405 ? VK_INDEX_TYPE_UINT32 : VK_INDEX_TYPE_UINT16;
                    if (indexType == 0x1401) return; // byte indices are not used by the game's renderers
                    eb.vk.lastUse = frame;
                    if (kind == DRAW_ELEMENTS) {
                        int indexSize = vkType == VK_INDEX_TYPE_UINT32 ? 4 : 2;
                        vkCmdBindIndexBuffer(cmd, eb.vk.buffer, 0, vkType);
                        vkCmdDrawIndexed(cmd, count, Math.max(1, instances), first / indexSize, baseVertex, 0);
                    } else {
                        GLResources.GLBuffer ib = res.bufferAt(0x8F3F);
                        if (ib == null || ib.vk == null) return;
                        ib.vk.lastUse = frame;
                        vkCmdBindIndexBuffer(cmd, eb.vk.buffer, 0, vkType);
                        vkCmdDrawIndexedIndirect(cmd, ib.vk.buffer, first, count, stride == 0 ? 20 : stride);
                    }
                    break;
                }
                default:
                    break;
            }
        }
    }

    private static int topology(int mode) {
        switch (mode) {
            case 0x0000:
                return VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
            case 0x0001:
                return VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case 0x0002:
            case 0x0003:
                return VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
            case 0x0005:
                return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
            case 0x0006:
                return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;
            default:
                return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        }
    }

    private static int topologyClass(int mode) {
        int t = topology(mode);
        return t == VK_PRIMITIVE_TOPOLOGY_POINT_LIST ? 0 : t <= VK_PRIMITIVE_TOPOLOGY_LINE_STRIP ? 1 : 2;
    }

    private void dynamicState(VkCommandBuffer cmd, RenderState s, int mode) {
        try (MemoryStack stack = stackPush()) {
            VkViewport.Buffer vp = VkViewport.calloc(1, stack);
            vp.get(0)
                .x(s.viewport[0])
                .y(s.viewport[1])
                .width(Math.max(1, s.viewport[2] == 0 ? targetWidth : s.viewport[2]))
                .height(Math.max(1, s.viewport[3] == 0 ? targetHeight : s.viewport[3]))
                .minDepth(0f)
                .maxDepth(1f);
            vkCmdSetViewport(cmd, 0, vp);
            VkRect2D.Buffer sc = VkRect2D.calloc(1, stack);
            if (s.scissorTest) {
                int x = clamp(s.scissor[0], 0, targetWidth), y = clamp(s.scissor[1], 0, targetHeight);
                sc.get(0)
                    .offset()
                    .set(x, y);
                sc.get(0)
                    .extent()
                    .set(clamp(s.scissor[2], 0, targetWidth - x), clamp(s.scissor[3], 0, targetHeight - y));
            } else {
                sc.get(0)
                    .extent()
                    .set(targetWidth, targetHeight);
            }
            vkCmdSetScissor(cmd, 0, sc);
        }
        vkCmdSetLineWidth(cmd, 1f);
        vkCmdSetDepthBiasEnable(cmd, s.polygonOffsetFill);
        if (s.polygonOffsetFill) vkCmdSetDepthBias(cmd, s.offsetUnits, 0f, s.offsetFactor);
        vkCmdSetBlendConstants(cmd, s.blendColor);
        vkCmdSetCullMode(
            cmd,
            !s.cullFace ? VK_CULL_MODE_NONE
                : s.cullMode == 0x0404 ? VK_CULL_MODE_FRONT_BIT
                    : s.cullMode == 0x0408 ? VK_CULL_MODE_FRONT_AND_BACK : VK_CULL_MODE_BACK_BIT);
        // OpenGL's counter-clockwise front faces look clockwise in Vulkan's framebuffer coordinates.
        vkCmdSetFrontFace(cmd, s.frontFace == 0x0901 ? VK_FRONT_FACE_CLOCKWISE : VK_FRONT_FACE_COUNTER_CLOCKWISE);
        vkCmdSetPrimitiveTopology(cmd, topology(mode));
        vkCmdSetPrimitiveRestartEnable(
            cmd,
            s.primitiveRestart && topologyClass(mode) != 0
                && topology(mode) != VK_PRIMITIVE_TOPOLOGY_LINE_LIST
                && topology(mode) != VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
        boolean depth = targetDepthFormat != VK_FORMAT_UNDEFINED;
        vkCmdSetDepthTestEnable(cmd, depth && s.depthTest);
        vkCmdSetDepthWriteEnable(cmd, depth && s.depthTest && s.depthMask);
        vkCmdSetDepthCompareOp(cmd, Math.max(0, Math.min(7, s.depthFunc - 0x0200)));
        boolean stencil = targetHasStencil && s.stencilTest;
        vkCmdSetStencilTestEnable(cmd, stencil);
        if (stencil) {
            int face = VK_STENCIL_FACE_FRONT_AND_BACK;
            vkCmdSetStencilOp(
                cmd,
                face,
                stencilOp(s.stencilFail),
                stencilOp(s.stencilPass),
                stencilOp(s.stencilDepthFail),
                Math.max(0, Math.min(7, s.stencilFunc - 0x0200)));
            vkCmdSetStencilCompareMask(cmd, face, s.stencilValueMask);
            vkCmdSetStencilWriteMask(cmd, face, s.stencilWriteMask);
            vkCmdSetStencilReference(cmd, face, s.stencilRef);
        }
    }

    private static int stencilOp(int gl) {
        switch (gl) {
            case 0:
                return VK_STENCIL_OP_ZERO;
            case 0x1E01:
                return VK_STENCIL_OP_REPLACE;
            case 0x1E02:
                return VK_STENCIL_OP_INCREMENT_AND_CLAMP;
            case 0x1E03:
                return VK_STENCIL_OP_DECREMENT_AND_CLAMP;
            case 0x150A:
                return VK_STENCIL_OP_INVERT;
            case 0x8507:
                return VK_STENCIL_OP_INCREMENT_AND_WRAP;
            case 0x8508:
                return VK_STENCIL_OP_DECREMENT_AND_WRAP;
            default:
                return VK_STENCIL_OP_KEEP;
        }
    }

    // ------------------------------------------------------------------ vertex input

    /** The Vulkan vertex format of an attribute array; 3-component 8/16-bit formats are widened to 4 components. */
    static int vertexFormat(int type, int size, boolean normalized, boolean integer) {
        int n = Math.max(1, Math.min(4, size));
        switch (type) {
            case 0x1406: // FLOAT
                return n == 1 ? VK_FORMAT_R32_SFLOAT
                    : n == 2 ? VK_FORMAT_R32G32_SFLOAT
                        : n == 3 ? VK_FORMAT_R32G32B32_SFLOAT : VK_FORMAT_R32G32B32A32_SFLOAT;
            case 0x140B: // HALF_FLOAT
                return n == 1 ? VK_FORMAT_R16_SFLOAT : n == 2 ? VK_FORMAT_R16G16_SFLOAT : VK_FORMAT_R16G16B16A16_SFLOAT;
            case 0x1401: // UNSIGNED_BYTE
                if (integer) return n == 1 ? VK_FORMAT_R8_UINT : n == 2 ? VK_FORMAT_R8G8_UINT : VK_FORMAT_R8G8B8A8_UINT;
                if (normalized)
                    return n == 1 ? VK_FORMAT_R8_UNORM : n == 2 ? VK_FORMAT_R8G8_UNORM : VK_FORMAT_R8G8B8A8_UNORM;
                return n == 1 ? VK_FORMAT_R8_USCALED : n == 2 ? VK_FORMAT_R8G8_USCALED : VK_FORMAT_R8G8B8A8_USCALED;
            case 0x1400: // BYTE
                if (integer) return n == 1 ? VK_FORMAT_R8_SINT : n == 2 ? VK_FORMAT_R8G8_SINT : VK_FORMAT_R8G8B8A8_SINT;
                if (normalized)
                    return n == 1 ? VK_FORMAT_R8_SNORM : n == 2 ? VK_FORMAT_R8G8_SNORM : VK_FORMAT_R8G8B8A8_SNORM;
                return n == 1 ? VK_FORMAT_R8_SSCALED : n == 2 ? VK_FORMAT_R8G8_SSCALED : VK_FORMAT_R8G8B8A8_SSCALED;
            case 0x1403: // UNSIGNED_SHORT
                if (integer)
                    return n == 1 ? VK_FORMAT_R16_UINT : n == 2 ? VK_FORMAT_R16G16_UINT : VK_FORMAT_R16G16B16A16_UINT;
                if (normalized) return n == 1 ? VK_FORMAT_R16_UNORM
                    : n == 2 ? VK_FORMAT_R16G16_UNORM : VK_FORMAT_R16G16B16A16_UNORM;
                return n == 1 ? VK_FORMAT_R16_USCALED
                    : n == 2 ? VK_FORMAT_R16G16_USCALED : VK_FORMAT_R16G16B16A16_USCALED;
            case 0x1402: // SHORT
                if (integer)
                    return n == 1 ? VK_FORMAT_R16_SINT : n == 2 ? VK_FORMAT_R16G16_SINT : VK_FORMAT_R16G16B16A16_SINT;
                if (normalized) return n == 1 ? VK_FORMAT_R16_SNORM
                    : n == 2 ? VK_FORMAT_R16G16_SNORM : VK_FORMAT_R16G16B16A16_SNORM;
                return n == 1 ? VK_FORMAT_R16_SSCALED
                    : n == 2 ? VK_FORMAT_R16G16_SSCALED : VK_FORMAT_R16G16B16A16_SSCALED;
            case 0x1405: // UNSIGNED_INT
                return n == 1 ? VK_FORMAT_R32_UINT
                    : n == 2 ? VK_FORMAT_R32G32_UINT : n == 3 ? VK_FORMAT_R32G32B32_UINT : VK_FORMAT_R32G32B32A32_UINT;
            case 0x1404: // INT
                return n == 1 ? VK_FORMAT_R32_SINT
                    : n == 2 ? VK_FORMAT_R32G32_SINT : n == 3 ? VK_FORMAT_R32G32B32_SINT : VK_FORMAT_R32G32B32A32_SINT;
            case 0x8368: // UNSIGNED_INT_2_10_10_10_REV
                return normalized ? VK_FORMAT_A2B10G10R10_UNORM_PACK32 : VK_FORMAT_A2B10G10R10_USCALED_PACK32;
            case 0x8D9F: // INT_2_10_10_10_REV
                return normalized ? VK_FORMAT_A2B10G10R10_SNORM_PACK32 : VK_FORMAT_A2B10G10R10_SSCALED_PACK32;
            default:
                return VK_FORMAT_R32G32B32A32_SFLOAT;
        }
    }

    /** Attribute arrays become one vertex buffer binding each; inputs without an array read a constant value. */
    private static final java.util.Set<Long> SEEN = new java.util.HashSet<>();

    private boolean bindVertices(VkCommandBuffer cmd, VulkanShaders.GLProgram p, VertexArray vao, RenderState s) {
        long frame = dev.currentFrame();
        try (MemoryStack stack = stackPush()) {
            if (Stats.ON && SEEN.add(-(long) p.id)) {
                StringBuilder sb = new StringBuilder();
                for (int loc : p.attribLocations.values()) if (loc >= 0 && loc < 16) sb.append(' ')
                    .append(loc)
                    .append(
                        vao.enabled[loc]
                            ? ":" + Integer.toHexString(vao.type[loc])
                                + "x"
                                + vao.size[loc]
                                + (vao.normalized[loc] ? "n" : "")
                                + (vao.integer[loc] ? "i" : "")
                                + " s"
                                + vao.stride[loc]
                                + " o"
                                + vao.offset[loc]
                            : ":const");
                LOG.info("program {} vertex inputs{}", p.id, sb);
            }
            for (int loc : p.attribLocations.values()) {
                if (loc < 0 || loc >= 16) continue;
                long buffer, offset, stride;
                if (vao.enabled[loc]) {
                    GLResources.GLBuffer b = res.buffers.get(vao.buffer[loc]);
                    if (b == null || b.vk == null) return false;
                    b.vk.lastUse = frame;
                    buffer = b.vk.buffer;
                    offset = vao.offset[loc];
                    stride = vao.stride[loc] != 0 ? vao.stride[loc]
                        : (long) Math.max(1, vao.size[loc]) * typeSize(vao.type[loc]);
                } else {
                    long at = ringWrite(s.attribValues[loc]);
                    buffer = ring.buffer;
                    offset = at;
                    stride = 0;
                }
                vkCmdBindVertexBuffers2(cmd, loc, stack.longs(buffer), stack.longs(offset), null, stack.longs(stride));
            }
        }
        return true;
    }

    private static int typeSize(int type) {
        switch (type) {
            case 0x1400:
            case 0x1401:
                return 1;
            case 0x1402:
            case 0x1403:
            case 0x140B:
                return 2;
            default:
                return 4;
        }
    }

    // ------------------------------------------------------------------ the per-frame ring

    private long ringReserve(long n) {
        long frame = dev.currentFrame();
        int s = (int) (frame % VulkanDevice.FRAMES_IN_FLIGHT);
        if (ringFrame[s] != frame) {
            ringFrame[s] = frame;
            ringUsed[s] = 0;
        }
        long aligned = (n + 255) & ~255L;
        VulkanBuffer r = rings[s];
        if (r == null || ringUsed[s] + aligned > r.size) {
            if (r != null) r.release(dev);
            r = new VulkanBuffer(dev, Math.max(16L << 20, aligned * 4));
            rings[s] = r;
            ringUsed[s] = 0;
        }
        ring = r;
        long at = ringUsed[s];
        ringUsed[s] += aligned;
        return at;
    }

    private long ringWrite(float[] values) {
        long at = ringReserve(16);
        for (int i = 0; i < 4; i++) MemoryUtil.memPutFloat(ring.address + at + i * 4L, values[i]);
        return at;
    }

    private long ringWrite(java.nio.ByteBuffer data, int size) {
        long at = ringReserve(size);
        MemoryUtil.memCopy(MemoryUtil.memAddress(data), ring.address + at, size);
        return at;
    }

    // ------------------------------------------------------------------ descriptors

    private void descriptors(VkCommandBuffer cmd, GLResources.Ctx c, VulkanShaders.GLProgram p) {
        int count = p.fsUboBinding + 1;
        try (MemoryStack stack = stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(count, stack);
            int n = 0;
            for (int i = 0; i < p.vsSamplers.size(); i++) n = texture(writes, n, i, c, p, p.vsSamplers.get(i), stack);
            n = values(writes, n, p.vsUboBinding, c, p, true, stack);
            for (int i = 0; i < p.fsSamplers.size(); i++)
                n = texture(writes, n, p.fsSamplerBase + i, c, p, p.fsSamplers.get(i), stack);
            n = values(writes, n, p.fsUboBinding, c, p, false, stack);
            writes.limit(n);
            if (n > 0) KHRPushDescriptor
                .vkCmdPushDescriptorSetKHR(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, p.pipelineLayout, 0, writes);
        }
    }

    private int texture(VkWriteDescriptorSet.Buffer writes, int n, int binding, GLResources.Ctx c,
        VulkanShaders.GLProgram p, String name, MemoryStack stack) {
        VulkanShaders.Uniform u = p.uniforms.get(name);
        int unit = u == null ? 0 : Math.max(0, Math.min(GLResources.UNITS - 1, u.unit));
        GLResources.GLTexture tex = res.textures.get(c.boundTextures[unit]);
        if (tex == null || tex.image == null) tex = blank();
        if (Stats.ON && SEEN.add(p.id * 100000L + tex.id)) LOG.info(
            "program {} samples {} with texture {}: {}x{} levels {} min {} mag {} aniso {} maxLevel {} maxLod {} wrap {}/{}",
            p.id,
            name,
            tex.id,
            tex.image.width,
            tex.image.height,
            tex.image.levels,
            Integer.toHexString(tex.minFilter),
            Integer.toHexString(tex.magFilter),
            tex.anisotropy,
            tex.maxLevel,
            tex.maxLod,
            Integer.toHexString(tex.wrapS),
            Integer.toHexString(tex.wrapT));
        VkDescriptorImageInfo.Buffer image = VkDescriptorImageInfo.calloc(1, stack);
        image.get(0)
            .sampler(sampler(tex))
            .imageView(tex.image.view)
            .imageLayout(VK_IMAGE_LAYOUT_GENERAL);
        writes.get(n)
            .sType$Default()
            .dstBinding(binding)
            .descriptorCount(1)
            .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .pImageInfo(image);
        return n + 1;
    }

    private int values(VkWriteDescriptorSet.Buffer writes, int n, int binding, GLResources.Ctx c,
        VulkanShaders.GLProgram p, boolean vertex, MemoryStack stack) {
        int size = vertex ? p.vsUboSize : p.fsUboSize;
        if (size <= 0) return n;
        long buffer, offset;
        if (p.externalUboBinding >= 0 && p.externalUboBinding < c.uboBuffer.length
            && c.uboBuffer[p.externalUboBinding] != 0) {
            GLResources.GLBuffer b = res.buffers.get(c.uboBuffer[p.externalUboBinding]);
            if (b == null || b.vk == null) return n;
            b.vk.lastUse = dev.currentFrame();
            buffer = b.vk.buffer;
            offset = c.uboOffset[p.externalUboBinding];
            size = (int) Math.min(size, b.vk.size - offset);
        } else {
            java.nio.ByteBuffer v = vertex ? p.vsValues : p.fsValues;
            if (v == null) return n;
            offset = ringWrite(v, size);
            buffer = ring.buffer;
        }
        VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
        info.get(0)
            .buffer(buffer)
            .offset(offset)
            .range(size);
        writes.get(n)
            .sType$Default()
            .dstBinding(binding)
            .descriptorCount(1)
            .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
            .pBufferInfo(info);
        return n + 1;
    }

    private GLResources.GLTexture blank() {
        if (blank == null) {
            blank = new GLResources.GLTexture(-1);
            res.define(blank, Formats.GL_RGBA8, 1, 1, 1);
            java.nio.ByteBuffer white = MemoryUtil.memAlloc(4);
            white.putInt(0, -1);
            res.upload(blank, 0, 0, 0, 1, 1, Formats.GL_RGBA, Formats.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(white));
            MemoryUtil.memFree(white);
        }
        return blank;
    }

    private long sampler(GLResources.GLTexture t) {
        boolean mip = t.minFilter >= 0x2700 && t.image.levels > 1;
        boolean minLinear = t.minFilter == 0x2601 || t.minFilter == 0x2701 || t.minFilter == 0x2703;
        boolean mipLinear = t.minFilter == 0x2702 || t.minFilter == 0x2703;
        boolean magLinear = t.magFilter == 0x2601;
        int aniso = Math.max(1, Math.min(16, (int) t.anisotropy));
        long key = (minLinear ? 1 : 0) | (magLinear ? 2 : 0)
            | (mipLinear ? 4 : 0)
            | (mip ? 8 : 0)
            | (long) wrap(t.wrapS) << 4
            | (long) wrap(t.wrapT) << 8
            | (long) aniso << 12
            | (long) (t.compareMode != 0 ? 1 : 0) << 20
            | (long) (t.compareFunc & 7) << 21
            | (long) Math.min(15, Math.max(0, t.maxLevel)) << 24;
        Long cached = samplers.get(key);
        if (cached != null) return cached;
        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack)
                .sType$Default()
                .magFilter(magLinear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST)
                .minFilter(minLinear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST)
                .mipmapMode(mipLinear ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(wrap(t.wrapS))
                .addressModeV(wrap(t.wrapT))
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                .anisotropyEnable(aniso > 1)
                .maxAnisotropy(Math.min(aniso, dev.maxAnisotropy()))
                .compareEnable(t.compareMode != 0)
                .compareOp(t.compareFunc & 7)
                .minLod(0f)
                .maxLod(mip ? Math.min(t.maxLod, t.maxLevel) : 0.25f)
                .borderColor(VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK);
            LongBuffer handle = stack.mallocLong(1);
            VulkanDevice.check(vkCreateSampler(dev.vk(), info, null, handle), "create a sampler");
            samplers.put(key, handle.get(0));
            return handle.get(0);
        }
    }

    private static int wrap(int gl) {
        switch (gl) {
            case 0x2900: // CLAMP
            case 0x812F: // CLAMP_TO_EDGE
                return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            case 0x812D:
                return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER;
            case 0x8370:
                return VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
            default:
                return VK_SAMPLER_ADDRESS_MODE_REPEAT;
        }
    }

    // ------------------------------------------------------------------ pipelines

    private static final class PipelineKey {

        final long[] parts;
        final int hash;

        PipelineKey(long[] parts) {
            this.parts = parts;
            this.hash = Arrays.hashCode(parts);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PipelineKey && Arrays.equals(parts, ((PipelineKey) o).parts);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private long pipeline(VulkanShaders.GLProgram p, VertexArray vao, RenderState s, int mode) {
        long[] k = new long[24];
        int i = 0;
        k[i++] = (long) p.id << 32 | p.version;
        long inputs = 0;
        int j = 0;
        for (int loc : p.attribLocations.values()) {
            if (loc < 0 || loc >= 16) continue;
            long fmt = vao.enabled[loc]
                ? vertexFormat(vao.type[loc], vao.size[loc], vao.normalized[loc], vao.integer[loc])
                : VK_FORMAT_R32G32B32A32_SFLOAT;
            long rate = vao.enabled[loc] && vao.divisor[loc] != 0 ? 1 : 0;
            k[1 + (j++ % 16)] ^= (loc + 1L) << 40 | fmt << 8 | rate;
            inputs |= 1L << loc;
        }
        i = 17;
        k[i++] = inputs;
        k[i++] = (s.blend ? 1L : 0) | (long) s.srcRGB << 1
            | (long) s.dstRGB << 15
            | (long) s.srcAlpha << 29
            | (long) s.dstAlpha << 43;
        k[i++] = (long) s.eqRGB << 32 | s.eqAlpha;
        k[i++] = (s.colorMask[0] ? 1 : 0) | (s.colorMask[1] ? 2 : 0)
            | (s.colorMask[2] ? 4 : 0)
            | (s.colorMask[3] ? 8 : 0)
            | (long) topologyClass(mode) << 4
            | (long) s.polygonMode << 8;
        long formats = targetDepthFormat;
        for (int f : targetColorFormats) formats = formats * 1000003L + f;
        k[i++] = formats;
        k[i++] = targetColorFormats.length;
        PipelineKey key = new PipelineKey(k);
        Long cached = pipelines.get(key);
        if (cached != null) return cached;
        if (Stats.ON) Stats.fPipelines++;
        long created = createPipeline(p, vao, s, mode);
        pipelines.put(key, created);
        return created;
    }

    private long createPipeline(VulkanShaders.GLProgram p, VertexArray vao, RenderState s, int mode) {
        try (MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0)
                .sType$Default()
                .stage(VK_SHADER_STAGE_VERTEX_BIT)
                .module(p.vsModule)
                .pName(stack.UTF8("main"));
            stages.get(1)
                .sType$Default()
                .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                .module(p.fsModule)
                .pName(stack.UTF8("main"));

            int count = 0;
            for (int loc : p.attribLocations.values()) if (loc >= 0 && loc < 16) count++;
            VkVertexInputBindingDescription.Buffer bindings = VkVertexInputBindingDescription.calloc(count, stack);
            VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription
                .calloc(count, stack);
            int n = 0;
            for (int loc : p.attribLocations.values()) {
                if (loc < 0 || loc >= 16) continue;
                boolean on = vao.enabled[loc];
                bindings.get(n)
                    .binding(loc)
                    .stride(0)
                    .inputRate(
                        on && vao.divisor[loc] != 0 ? VK_VERTEX_INPUT_RATE_INSTANCE : VK_VERTEX_INPUT_RATE_VERTEX);
                attributes.get(n)
                    .location(loc)
                    .binding(loc)
                    .format(
                        on ? vertexFormat(vao.type[loc], vao.size[loc], vao.normalized[loc], vao.integer[loc])
                            : VK_FORMAT_R32G32B32A32_SFLOAT)
                    .offset(0);
                n++;
            }
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType$Default()
                .pVertexBindingDescriptions(bindings)
                .pVertexAttributeDescriptions(attributes);
            VkPipelineInputAssemblyStateCreateInfo assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                .sType$Default()
                .topology(topology(mode));
            VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
                .sType$Default()
                .viewportCount(1)
                .scissorCount(1);
            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType$Default()
                .polygonMode(
                    s.polygonMode == 0x1B01 ? VK_POLYGON_MODE_LINE
                        : s.polygonMode == 0x1B00 ? VK_POLYGON_MODE_POINT : VK_POLYGON_MODE_FILL)
                .lineWidth(1f);
            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType$Default()
                .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                .sType$Default();
            int colors = targetColorFormats.length;
            VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState
                .calloc(colors, stack);
            int mask = (s.colorMask[0] ? VK_COLOR_COMPONENT_R_BIT : 0) | (s.colorMask[1] ? VK_COLOR_COMPONENT_G_BIT : 0)
                | (s.colorMask[2] ? VK_COLOR_COMPONENT_B_BIT : 0)
                | (s.colorMask[3] ? VK_COLOR_COMPONENT_A_BIT : 0);
            for (int a = 0; a < colors; a++) {
                blend.get(a)
                    .blendEnable(s.blend)
                    .srcColorBlendFactor(factor(s.srcRGB))
                    .dstColorBlendFactor(factor(s.dstRGB))
                    .srcAlphaBlendFactor(factor(s.srcAlpha))
                    .dstAlphaBlendFactor(factor(s.dstAlpha))
                    .colorBlendOp(equation(s.eqRGB))
                    .alphaBlendOp(equation(s.eqAlpha))
                    .colorWriteMask(mask);
            }
            VkPipelineColorBlendStateCreateInfo blendState = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType$Default()
                .pAttachments(blend);
            VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                .sType$Default()
                .pDynamicStates(
                    stack.ints(
                        VK_DYNAMIC_STATE_VIEWPORT,
                        VK_DYNAMIC_STATE_SCISSOR,
                        VK_DYNAMIC_STATE_LINE_WIDTH,
                        VK_DYNAMIC_STATE_DEPTH_BIAS,
                        VK_DYNAMIC_STATE_BLEND_CONSTANTS,
                        VK_DYNAMIC_STATE_STENCIL_COMPARE_MASK,
                        VK_DYNAMIC_STATE_STENCIL_WRITE_MASK,
                        VK_DYNAMIC_STATE_STENCIL_REFERENCE,
                        VK_DYNAMIC_STATE_CULL_MODE,
                        VK_DYNAMIC_STATE_FRONT_FACE,
                        VK_DYNAMIC_STATE_PRIMITIVE_TOPOLOGY,
                        VK_DYNAMIC_STATE_VERTEX_INPUT_BINDING_STRIDE,
                        VK_DYNAMIC_STATE_DEPTH_TEST_ENABLE,
                        VK_DYNAMIC_STATE_DEPTH_WRITE_ENABLE,
                        VK_DYNAMIC_STATE_DEPTH_COMPARE_OP,
                        VK_DYNAMIC_STATE_STENCIL_TEST_ENABLE,
                        VK_DYNAMIC_STATE_STENCIL_OP,
                        VK_DYNAMIC_STATE_DEPTH_BIAS_ENABLE,
                        VK_DYNAMIC_STATE_PRIMITIVE_RESTART_ENABLE));
            VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(stack)
                .sType$Default()
                .pColorAttachmentFormats(stack.ints(targetColorFormats))
                .depthAttachmentFormat(targetDepthFormat)
                .stencilAttachmentFormat(targetHasStencil ? targetDepthFormat : VK_FORMAT_UNDEFINED);
            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0)
                .sType$Default()
                .pNext(rendering.address())
                .pStages(stages)
                .pVertexInputState(vertexInput)
                .pInputAssemblyState(assembly)
                .pViewportState(viewport)
                .pRasterizationState(raster)
                .pMultisampleState(multisample)
                .pDepthStencilState(depth)
                .pColorBlendState(blendState)
                .pDynamicState(dynamic)
                .layout(p.pipelineLayout);
            LongBuffer handle = stack.mallocLong(1);
            int result = vkCreateGraphicsPipelines(dev.vk(), dev.pipelineCache(), info, null, handle);
            if (result != VK_SUCCESS) {
                LOG.error("Could not create a pipeline for program {} (error {})", p.id, result);
                return 0;
            }
            return handle.get(0);
        }
    }

    private static int factor(int gl) {
        switch (gl) {
            case 0:
                return VK_BLEND_FACTOR_ZERO;
            case 1:
                return VK_BLEND_FACTOR_ONE;
            case 0x0300:
                return VK_BLEND_FACTOR_SRC_COLOR;
            case 0x0301:
                return VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case 0x0302:
                return VK_BLEND_FACTOR_SRC_ALPHA;
            case 0x0303:
                return VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case 0x0304:
                return VK_BLEND_FACTOR_DST_ALPHA;
            case 0x0305:
                return VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case 0x0306:
                return VK_BLEND_FACTOR_DST_COLOR;
            case 0x0307:
                return VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case 0x0308:
                return VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
            case 0x8001:
                return VK_BLEND_FACTOR_CONSTANT_COLOR;
            case 0x8002:
                return VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
            case 0x8003:
                return VK_BLEND_FACTOR_CONSTANT_ALPHA;
            case 0x8004:
                return VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
            default:
                return VK_BLEND_FACTOR_ONE;
        }
    }

    private static int equation(int gl) {
        switch (gl) {
            case 0x800A:
                return VK_BLEND_OP_SUBTRACT;
            case 0x800B:
                return VK_BLEND_OP_REVERSE_SUBTRACT;
            case 0x8007:
                return VK_BLEND_OP_MIN;
            case 0x8008:
                return VK_BLEND_OP_MAX;
            default:
                return VK_BLEND_OP_ADD;
        }
    }

    // ------------------------------------------------------------------ copies between framebuffers

    /** The image a framebuffer reads colour from (glReadBuffer), with its level. */
    private VulkanImage readColor(int framebuffer, int[] level) {
        level[0] = 0;
        if (framebuffer == 0) {
            res.ensureScreen();
            return res.screenColor;
        }
        Framebuffer fb = framebuffers.get(framebuffer);
        if (fb == null) return null;
        int i = fb.readBuffer >= 0 && fb.readBuffer < 8 ? fb.readBuffer : 0;
        level[0] = fb.colorLevel[i];
        return colorImage(fb, i);
    }

    private VulkanImage drawColor(int framebuffer, int[] level) {
        level[0] = 0;
        if (framebuffer == 0) {
            res.ensureScreen();
            return res.screenColor;
        }
        Framebuffer fb = framebuffers.get(framebuffer);
        if (fb == null) return null;
        int i = fb.drawBuffers.length > 0 && fb.drawBuffers[0] >= 0 && fb.drawBuffers[0] < 8 ? fb.drawBuffers[0] : 0;
        level[0] = fb.colorLevel[i];
        return colorImage(fb, i);
    }

    private VulkanImage depthOf(int framebuffer) {
        if (framebuffer == 0) {
            res.ensureScreen();
            return res.screenDepth;
        }
        Framebuffer fb = framebuffers.get(framebuffer);
        return fb == null ? null : depthImage(fb);
    }

    /** glBlitFramebuffer from the read to the draw framebuffer. */
    void blit(GLResources.Ctx c, int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, int mask,
        int filter) {
        synchronized (res.lock) {
            int[] sl = new int[1], dl = new int[1];
            if ((mask & 0x4000) != 0) {
                VulkanImage src = readColor(c.readFramebuffer, sl), dst = drawColor(c.drawFramebuffer, dl);
                if (src != null && dst != null) blitImage(
                    src,
                    sl[0],
                    dst,
                    dl[0],
                    VK_IMAGE_ASPECT_COLOR_BIT,
                    sx0,
                    sy0,
                    sx1,
                    sy1,
                    dx0,
                    dy0,
                    dx1,
                    dy1,
                    filter == 0x2601 ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
            }
            if ((mask & 0x0500) != 0) {
                VulkanImage src = depthOf(c.readFramebuffer), dst = depthOf(c.drawFramebuffer);
                if (src != null && dst != null && src != dst && src.format == dst.format) {
                    int aspect = ((mask & 0x0100) != 0 ? VK_IMAGE_ASPECT_DEPTH_BIT : 0)
                        | ((mask & 0x0400) != 0 && Formats.hasStencil(src.format) ? VK_IMAGE_ASPECT_STENCIL_BIT : 0);
                    if (aspect != 0)
                        blitImage(src, 0, dst, 0, aspect, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, VK_FILTER_NEAREST);
                }
            }
        }
    }

    /** glCopyTexSubImage2D: from the read framebuffer into a texture level. */
    void copyToTexture(GLResources.Ctx c, GLResources.GLTexture tex, int level, int xoff, int yoff, int x, int y, int w,
        int h) {
        if (tex == null || tex.image == null || w <= 0 || h <= 0) return;
        synchronized (res.lock) {
            int[] sl = new int[1];
            if (tex.image.depth) {
                VulkanImage src = depthOf(c.readFramebuffer);
                if (src != null && src.format == tex.image.format) blitImage(
                    src,
                    0,
                    tex.image,
                    level,
                    VK_IMAGE_ASPECT_DEPTH_BIT,
                    x,
                    y,
                    x + w,
                    y + h,
                    xoff,
                    yoff,
                    xoff + w,
                    yoff + h,
                    VK_FILTER_NEAREST);
                return;
            }
            VulkanImage src = readColor(c.readFramebuffer, sl);
            if (src != null) blitImage(
                src,
                sl[0],
                tex.image,
                level,
                VK_IMAGE_ASPECT_COLOR_BIT,
                x,
                y,
                x + w,
                y + h,
                xoff,
                yoff,
                xoff + w,
                yoff + h,
                VK_FILTER_NEAREST);
        }
    }

    private void blitImage(VulkanImage src, int srcLevel, VulkanImage dst, int dstLevel, int aspect, int sx0, int sy0,
        int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, int filter) {
        int sw = Math.max(1, src.width >> srcLevel), sh = Math.max(1, src.height >> srcLevel);
        int dw = Math.max(1, dst.width >> dstLevel), dh = Math.max(1, dst.height >> dstLevel);
        sx0 = clamp(sx0, 0, sw);
        sx1 = clamp(sx1, 0, sw);
        sy0 = clamp(sy0, 0, sh);
        sy1 = clamp(sy1, 0, sh);
        dx0 = clamp(dx0, 0, dw);
        dx1 = clamp(dx1, 0, dw);
        dy0 = clamp(dy0, 0, dh);
        dy1 = clamp(dy1, 0, dh);
        if (sx0 == sx1 || sy0 == sy1 || dx0 == dx1 || dy0 == dy1) return;
        endRendering();
        VkCommandBuffer cmd = dev.cmd();
        Barriers.full(cmd);
        try (MemoryStack stack = stackPush()) {
            org.lwjgl.vulkan.VkImageBlit.Buffer region = org.lwjgl.vulkan.VkImageBlit.calloc(1, stack);
            region.get(0)
                .srcSubresource(
                    s -> s.aspectMask(aspect)
                        .mipLevel(srcLevel)
                        .layerCount(1));
            region.get(0)
                .srcOffsets(0)
                .set(sx0, sy0, 0);
            region.get(0)
                .srcOffsets(1)
                .set(sx1, sy1, 1);
            region.get(0)
                .dstSubresource(
                    s -> s.aspectMask(aspect)
                        .mipLevel(dstLevel)
                        .layerCount(1));
            region.get(0)
                .dstOffsets(0)
                .set(dx0, dy0, 0);
            region.get(0)
                .dstOffsets(1)
                .set(dx1, dy1, 1);
            vkCmdBlitImage(cmd, src.image, VK_IMAGE_LAYOUT_GENERAL, dst.image, VK_IMAGE_LAYOUT_GENERAL, region, filter);
        }
        Barriers.full(cmd);
    }

    /** Frees a renderbuffer's image when it is deleted or re-sized. */
    void releaseRenderbuffer(Renderbuffer rb) {
        if (rb != null && rb.image != null) synchronized (res.lock) {
            endRendering();
            rb.image.release(dev);
            rb.image = null;
        }
    }

    // ------------------------------------------------------------------ debug captures

    private static final java.util.Set<Long> SHOTS = new java.util.HashSet<>();
    static {
        String v = System.getenv("CLIENTM_VK_SHOT");
        if (v != null) for (String part : v.split(",")) {
            try {
                SHOTS.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {}
        }
    }

    private VulkanBuffer shotBuffer;
    private long shotFrame;
    private int shotWidth, shotHeight;

    /** Development aid (CLIENTM_VK_SHOT=frame,frame...): records a copy of the screen image of those frames. */
    void captureBeforePresent() {
        long frame = dev.currentFrame();
        if (!SHOTS.contains(frame) || res.screenColor == null) return;
        VulkanImage img = res.screenColor;
        endRendering();
        shotWidth = img.width;
        shotHeight = img.height;
        shotBuffer = new VulkanBuffer(dev, (long) shotWidth * shotHeight * 4);
        shotFrame = frame;
        VkCommandBuffer cmd = dev.cmd();
        Barriers.full(cmd);
        try (MemoryStack stack = stackPush()) {
            org.lwjgl.vulkan.VkBufferImageCopy.Buffer region = org.lwjgl.vulkan.VkBufferImageCopy.calloc(1, stack);
            region.get(0)
                .imageSubresource(
                    s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .layerCount(1));
            region.get(0)
                .imageExtent()
                .set(shotWidth, shotHeight, 1);
            vkCmdCopyImageToBuffer(cmd, img.image, VK_IMAGE_LAYOUT_GENERAL, shotBuffer.buffer, region);
        }
        Barriers.full(cmd);
    }

    /** Writes the copy recorded by {@link #captureBeforePresent} once the frame has been submitted. */
    void captureAfterPresent() {
        if (shotBuffer == null) return;
        dev.waitFor(shotFrame);
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
            shotWidth,
            shotHeight,
            java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < shotHeight; y++) {
            for (int x = 0; x < shotWidth; x++) {
                int p = MemoryUtil.memGetInt(shotBuffer.address + ((long) y * shotWidth + x) * 4);
                int r = p & 0xFF, g = (p >> 8) & 0xFF, b = (p >> 16) & 0xFF;
                out.setRGB(x, shotHeight - 1 - y, r << 16 | g << 8 | b);
            }
        }
        try {
            java.io.File dir = new java.io.File("screenshots");
            dir.mkdirs();
            javax.imageio.ImageIO.write(out, "png", new java.io.File(dir, "vk-" + shotFrame + ".png"));
            LOG.info("Saved Vulkan capture of frame {}", shotFrame);
        } catch (java.io.IOException e) {
            LOG.error("Could not save the Vulkan capture", e);
        }
        shotBuffer.release(dev);
        shotBuffer = null;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
