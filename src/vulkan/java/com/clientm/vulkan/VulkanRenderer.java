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

    final IdMap<VertexArray> vaos = new IdMap<>();
    final IdMap<Framebuffer> framebuffers = new IdMap<>();
    final IdMap<Renderbuffer> renderbuffers = new IdMap<>();

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
    private boolean targetFlipped;

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
        /** Rows run top-down (the window's own image): OpenGL's bottom-up coordinates are flipped when drawing. */
        boolean flipped;
    }

    private Target target(int framebuffer) {
        Target t = new Target();
        if (framebuffer == 0) {
            res.ensureScreen();
            VulkanImage window = dev.acquireTarget();
            if (window != null && window.width == res.screenDepth.width && window.height == res.screenDepth.height) {
                t.colors = new VulkanImage[] { window };
                t.flipped = true;
            } else {
                t.colors = new VulkanImage[] { res.screenColor };
            }
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
            boolean anyTopDown = false;
            for (VulkanImage img : t.colors) anyTopDown |= img.topDown;
            if (framebuffer == flippedFramebuffer || anyTopDown) {
                // Drawn top-down, so the finished frame goes to the window as a plain copy.
                t.flipped = true;
                for (VulkanImage img : t.colors) img.topDown = true;
                if (t.depth != null) t.depth.topDown = true;
            }
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

    /** Bumped by the backend whenever a framebuffer's attachments or draw buffers change. */
    int framebufferEdits;
    private int renderingFramebuffer = -1, renderingGeneration, renderingEdits;

    /** Makes sure rendering into the draw framebuffer is in progress; false when it has nothing to draw into. */
    private boolean beginRendering(int framebuffer) {
        // Still drawing into the same framebuffer, and no image or attachment has changed since: nothing to do.
        if (rendering && framebuffer == renderingFramebuffer
            && renderingGeneration == VulkanImage.generation
            && renderingEdits == framebufferEdits) return true;
        Target t = target(framebuffer);
        if (t == null) return false;
        if (rendering && t.key == targetKey) {
            renderingFramebuffer = framebuffer;
            renderingGeneration = VulkanImage.generation;
            renderingEdits = framebufferEdits;
            return true;
        }
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
        targetFlipped = t.flipped;
        renderingFramebuffer = framebuffer;
        renderingGeneration = VulkanImage.generation;
        renderingEdits = framebufferEdits;
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
        // A depth or stencil clear does not change the picture: it does not need the framebuffer copy made real
        // (Minecraft clears the screen's depth after the copy, for the stream indicator it no longer draws).
        if (c.drawFramebuffer == 0 && screenSource != null && (mask & 0x4000) != 0) {
            if (droppingScreenCopy) return;
            materializeScreen();
        }
        // A whole-screen clear is kept back: Minecraft clears the screen at the start of every frame and then covers
        // it with its framebuffer, so the clear only has to happen if something else draws on the screen.
        if (c.drawFramebuffer == 0 && !c.state.scissorTest && dev.directTarget() == null) {
            RenderState st = c.state;
            boolean wantColor = (mask & 0x4000) != 0;
            // Only plain whole-screen clears are kept back; a colour clear with some channels masked is done now.
            if (!wantColor || st.colorMask[0] && st.colorMask[1] && st.colorMask[2]) {
                endRendering(); // a later draw into the screen must start a new pass, after the clear
                if (wantColor) {
                    pendingScreenClear |= 0x4000;
                    pendingClearColor[0] = c.clearR;
                    pendingClearColor[1] = c.clearG;
                    pendingClearColor[2] = c.clearB;
                    pendingClearColor[3] = c.clearA;
                }
                if ((mask & 0x0100) != 0 && st.depthMask) {
                    pendingScreenClear |= 0x0100;
                    pendingClearDepth = c.clearDepth;
                }
                if ((mask & 0x0400) != 0) {
                    pendingScreenClear |= 0x0400;
                    pendingClearStencil = c.clearStencil;
                }
                return;
            }
        }
        if (c.drawFramebuffer == 0) applyScreenClear();
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
            if (targetFlipped) y = targetHeight - (y + h);
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
            if (c.drawFramebuffer == 0) {
                if (droppingScreenCopy) return;
                if (screenSource != null) materializeScreen();
                else applyScreenClear();
            }
            blank(); // created before rendering starts: creating an image ends rendering
            if (res.hasPendingUploads() && samplesPending(c, p)) res.flushUploads();
            if (!beginRendering(c.drawFramebuffer)) return;
            if (Stats.ON) dev.stamp(
                "p" + p.id
                    + (kind == DRAW_INDIRECT ? " indirect" : kind == DRAW_ELEMENTS ? " elements" : " arrays")
                    + (c.state.blend ? " blend" : ""));
            VkCommandBuffer cmd = dev.cmd();
            if (cacheEpoch != dev.epoch) resetCaches();
            prepare(p);
            RenderState s = c.state;
            long pipeline = pipeline(p, vao, s, mode);
            if (pipeline == 0) return;
            if (pipeline != boundPipeline) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
                boundPipeline = pipeline;
            }
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

    // ------------------------------------------------------------------ what this command buffer already has

    /** The command buffer epoch the remembered state below belongs to. */
    private int cacheEpoch = -1;
    private long boundPipeline;
    // dynamic state as last set; Integer.MIN_VALUE / NaN = not set yet
    private int dsVx, dsVy, dsVw, dsVh, dsSx, dsSy, dsSw, dsSh, dsCull, dsFront, dsTopology, dsDepthCompare;
    private int dsFlags, dsStencilOps, dsStencilMasks, dsStencilRef;
    private float dsBiasUnits, dsBiasFactor, dsBlendR, dsBlendG, dsBlendB, dsBlendA;
    private boolean dsLineWidthSet;
    // vertex buffer bindings as last set
    private final long[] vbBuffer = new long[16], vbOffset = new long[16], vbStride = new long[16];
    // constant attribute values last written to the ring, and where
    private final float[][] constValues = new float[16][4];
    private final long[] constOffset = new long[16];
    private final long[] constBuffer = new long[16];
    // descriptors as last pushed
    private VulkanShaders.GLProgram pushedProgram;
    private final long[] pushed = new long[64], pushing = new long[64];
    private int pushedLength;
    private long pushedLayout;

    private void resetCaches() {
        cacheEpoch = dev.epoch;
        boundPipeline = 0;
        dsVx = dsVy = dsVw = dsVh = dsSx = dsSy = dsSw = dsSh = Integer.MIN_VALUE;
        dsCull = dsFront = dsTopology = dsDepthCompare = dsFlags = Integer.MIN_VALUE;
        dsStencilOps = dsStencilMasks = dsStencilRef = Integer.MIN_VALUE;
        dsBiasUnits = dsBiasFactor = Float.NaN;
        dsBlendR = dsBlendG = dsBlendB = dsBlendA = Float.NaN;
        dsLineWidthSet = false;
        Arrays.fill(vbBuffer, -1);
        Arrays.fill(constBuffer, 0);
        pushedProgram = null;
        pushedLength = -1;
    }

    /** Works out, once per link, what each draw of this program needs: its attribute slots and sampler units. */
    private static void prepare(VulkanShaders.GLProgram p) {
        if (p.preparedVersion == p.version) return;
        int[] locs = new int[p.attribLocations.size()];
        int n = 0;
        for (int loc : p.attribLocations.values()) if (loc >= 0 && loc < 16) locs[n++] = loc;
        p.locations = Arrays.copyOf(locs, n);
        p.vsSamplerUniforms = new VulkanShaders.Uniform[p.vsSamplers.size()];
        for (int i = 0; i < p.vsSamplerUniforms.length; i++)
            p.vsSamplerUniforms[i] = p.uniforms.get(p.vsSamplers.get(i));
        p.fsSamplerUniforms = new VulkanShaders.Uniform[p.fsSamplers.size()];
        for (int i = 0; i < p.fsSamplerUniforms.length; i++)
            p.fsSamplerUniforms[i] = p.uniforms.get(p.fsSamplers.get(i));
        p.preparedVersion = p.version;
    }

    /** Sets the dynamic state a draw needs, skipping whatever already has that value in this command buffer. */
    private void dynamicState(VkCommandBuffer cmd, RenderState s, int mode) {
        int vw = Math.max(1, s.viewport[2] == 0 ? targetWidth : s.viewport[2]);
        int vh = Math.max(1, s.viewport[3] == 0 ? targetHeight : s.viewport[3]);
        int vx = s.viewport[0], vy = targetFlipped ? targetHeight - s.viewport[1] : s.viewport[1];
        if (targetFlipped) vh = -vh;
        if (vx != dsVx || vy != dsVy || vw != dsVw || vh != dsVh) {
            try (MemoryStack stack = stackPush()) {
                VkViewport.Buffer vp = VkViewport.calloc(1, stack);
                vp.get(0)
                    .x(vx)
                    .y(vy)
                    .width(vw)
                    .height(vh)
                    .minDepth(0f)
                    .maxDepth(1f);
                vkCmdSetViewport(cmd, 0, vp);
            }
            dsVx = vx;
            dsVy = vy;
            dsVw = vw;
            dsVh = vh;
        }
        int sx = 0, sy = 0, sw = targetWidth, sh = targetHeight;
        if (s.scissorTest) {
            sx = clamp(s.scissor[0], 0, targetWidth);
            sy = clamp(s.scissor[1], 0, targetHeight);
            sw = clamp(s.scissor[2], 0, targetWidth - sx);
            sh = clamp(s.scissor[3], 0, targetHeight - sy);
            if (targetFlipped) sy = targetHeight - (sy + sh);
        }
        if (sx != dsSx || sy != dsSy || sw != dsSw || sh != dsSh) {
            try (MemoryStack stack = stackPush()) {
                VkRect2D.Buffer sc = VkRect2D.calloc(1, stack);
                sc.get(0)
                    .offset()
                    .set(sx, sy);
                sc.get(0)
                    .extent()
                    .set(sw, sh);
                vkCmdSetScissor(cmd, 0, sc);
            }
            dsSx = sx;
            dsSy = sy;
            dsSw = sw;
            dsSh = sh;
        }
        if (!dsLineWidthSet) {
            vkCmdSetLineWidth(cmd, 1f);
            dsLineWidthSet = true;
        }
        if (s.polygonOffsetFill && (s.offsetUnits != dsBiasUnits || s.offsetFactor != dsBiasFactor)) {
            vkCmdSetDepthBias(cmd, s.offsetUnits, 0f, s.offsetFactor);
            dsBiasUnits = s.offsetUnits;
            dsBiasFactor = s.offsetFactor;
        }
        float[] bc = s.blendColor;
        if (bc[0] != dsBlendR || bc[1] != dsBlendG || bc[2] != dsBlendB || bc[3] != dsBlendA) {
            vkCmdSetBlendConstants(cmd, bc);
            dsBlendR = bc[0];
            dsBlendG = bc[1];
            dsBlendB = bc[2];
            dsBlendA = bc[3];
        }
        int cull = !s.cullFace ? VK_CULL_MODE_NONE
            : s.cullMode == 0x0404 ? VK_CULL_MODE_FRONT_BIT
                : s.cullMode == 0x0408 ? VK_CULL_MODE_FRONT_AND_BACK : VK_CULL_MODE_BACK_BIT;
        if (cull != dsCull) {
            vkCmdSetCullMode(cmd, cull);
            dsCull = cull;
        }
        // OpenGL's counter-clockwise front faces look clockwise in Vulkan's framebuffer coordinates.
        // Drawing flipped (into the window's image) turns the winding back again.
        int front = (s.frontFace == 0x0901) != targetFlipped ? VK_FRONT_FACE_CLOCKWISE
            : VK_FRONT_FACE_COUNTER_CLOCKWISE;
        if (front != dsFront) {
            vkCmdSetFrontFace(cmd, front);
            dsFront = front;
        }
        int topology = topology(mode);
        if (topology != dsTopology) {
            vkCmdSetPrimitiveTopology(cmd, topology);
            dsTopology = topology;
        }
        boolean depth = targetDepthFormat != VK_FORMAT_UNDEFINED;
        boolean stencil = targetHasStencil && s.stencilTest;
        boolean restart = s.primitiveRestart && topologyClass(mode) != 0
            && topology != VK_PRIMITIVE_TOPOLOGY_LINE_LIST
            && topology != VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        int flags = (s.polygonOffsetFill ? 1 : 0) | (restart ? 2 : 0)
            | (depth && s.depthTest ? 4 : 0)
            | (depth && s.depthTest && s.depthMask ? 8 : 0)
            | (stencil ? 16 : 0);
        if (flags != dsFlags) {
            int changed = dsFlags == Integer.MIN_VALUE ? -1 : flags ^ dsFlags;
            if ((changed & 1) != 0) vkCmdSetDepthBiasEnable(cmd, (flags & 1) != 0);
            if ((changed & 2) != 0) vkCmdSetPrimitiveRestartEnable(cmd, (flags & 2) != 0);
            if ((changed & 4) != 0) vkCmdSetDepthTestEnable(cmd, (flags & 4) != 0);
            if ((changed & 8) != 0) vkCmdSetDepthWriteEnable(cmd, (flags & 8) != 0);
            if ((changed & 16) != 0) vkCmdSetStencilTestEnable(cmd, (flags & 16) != 0);
            dsFlags = flags;
        }
        int compare = Math.max(0, Math.min(7, s.depthFunc - 0x0200));
        if (compare != dsDepthCompare) {
            vkCmdSetDepthCompareOp(cmd, compare);
            dsDepthCompare = compare;
        }
        if (stencil) {
            int face = VK_STENCIL_FACE_FRONT_AND_BACK;
            int ops = stencilOp(s.stencilFail) | stencilOp(s.stencilPass) << 4
                | stencilOp(s.stencilDepthFail) << 8
                | Math.max(0, Math.min(7, s.stencilFunc - 0x0200)) << 12;
            if (ops != dsStencilOps) {
                vkCmdSetStencilOp(
                    cmd,
                    face,
                    stencilOp(s.stencilFail),
                    stencilOp(s.stencilPass),
                    stencilOp(s.stencilDepthFail),
                    Math.max(0, Math.min(7, s.stencilFunc - 0x0200)));
                dsStencilOps = ops;
            }
            int masks = (s.stencilValueMask & 0xFF) | (s.stencilWriteMask & 0xFF) << 8;
            if (masks != dsStencilMasks) {
                vkCmdSetStencilCompareMask(cmd, face, s.stencilValueMask);
                vkCmdSetStencilWriteMask(cmd, face, s.stencilWriteMask);
                dsStencilMasks = masks;
            }
            if (s.stencilRef != dsStencilRef) {
                vkCmdSetStencilReference(cmd, face, s.stencilRef);
                dsStencilRef = s.stencilRef;
            }
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

    /** True when this draw would sample an image with a queued upload, which must be recorded first. */
    private boolean samplesPending(GLResources.Ctx c, VulkanShaders.GLProgram p) {
        for (int i = 0; i < 2; i++) {
            for (String name : i == 0 ? p.vsSamplers : p.fsSamplers) {
                VulkanShaders.Uniform u = p.uniforms.get(name);
                int unit = u == null ? 0 : Math.max(0, Math.min(GLResources.UNITS - 1, u.unit));
                GLResources.GLTexture tex = res.textures.get(c.boundTextures[unit]);
                if (tex != null && tex.image != null && tex.image.pendingUpload) return true;
            }
        }
        return false;
    }

    private boolean bindVertices(VkCommandBuffer cmd, VulkanShaders.GLProgram p, VertexArray vao, RenderState s) {
        long frame = dev.currentFrame();
        if (Stats.ON && SEEN.add(-(long) p.id)) logInputs(p, vao);
        for (int loc : p.locations) {
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
                // A constant value: written to the ring once, and reused while it stays the same this frame.
                float[] v = s.attribValues[loc], last = constValues[loc];
                if (constBuffer[loc] == 0 || v[0] != last[0] || v[1] != last[1] || v[2] != last[2] || v[3] != last[3]) {
                    constOffset[loc] = ringWrite(v);
                    constBuffer[loc] = ring.buffer;
                    System.arraycopy(v, 0, last, 0, 4);
                }
                buffer = constBuffer[loc];
                offset = constOffset[loc];
                stride = 0;
            }
            if (buffer == vbBuffer[loc] && offset == vbOffset[loc] && stride == vbStride[loc]) continue;
            try (MemoryStack stack = stackPush()) {
                vkCmdBindVertexBuffers2(cmd, loc, stack.longs(buffer), stack.longs(offset), null, stack.longs(stride));
            }
            vbBuffer[loc] = buffer;
            vbOffset[loc] = offset;
            vbStride[loc] = stride;
        }
        return true;
    }

    private void logInputs(VulkanShaders.GLProgram p, VertexArray vao) {
        StringBuilder sb = new StringBuilder();
        for (int loc : p.locations) sb.append(' ')
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

    /**
     * Pushes the program's textures and value blocks, unless the same program already has exactly these bound in this
     * command buffer. Value blocks are only copied to the ring again when a uniform changed.
     */
    private void descriptors(VkCommandBuffer cmd, GLResources.Ctx c, VulkanShaders.GLProgram p) {
        long frame = dev.currentFrame();
        if (p.ringFrame != frame) p.valuesDirty = true; // last frame's ring space is being reused
        if ((p.vsSamplerUniforms.length + p.fsSamplerUniforms.length) * 2 + 6 > pushing.length) return;
        int n = 0;
        for (VulkanShaders.Uniform u : p.vsSamplerUniforms) n = textureEntry(n, c, u);
        n = valuesEntry(n, c, p, true);
        for (VulkanShaders.Uniform u : p.fsSamplerUniforms) n = textureEntry(n, c, u);
        n = valuesEntry(n, c, p, false);
        p.valuesDirty = false;
        p.ringFrame = frame;
        if (p == pushedProgram && p.pipelineLayout == pushedLayout && n == pushedLength) {
            boolean same = true;
            for (int i = 0; i < n && same; i++) same = pushing[i] == pushed[i];
            if (same) return;
        }
        int writes = p.vsSamplerUniforms.length + p.fsSamplerUniforms.length + 2;
        try (MemoryStack stack = stackPush()) {
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(writes, stack);
            int k = 0, e = 0;
            for (int i = 0; i < p.vsSamplerUniforms.length; i++, e += 2) k = imageWrite(w, k, i, e, stack);
            if (p.vsUboSize > 0 && pushing[e] != 0) k = bufferWrite(w, k, p.vsUboBinding, e, stack);
            e += 3;
            for (int i = 0; i < p.fsSamplerUniforms.length; i++, e += 2)
                k = imageWrite(w, k, p.fsSamplerBase + i, e, stack);
            if (p.fsUboSize > 0 && pushing[e] != 0) k = bufferWrite(w, k, p.fsUboBinding, e, stack);
            w.limit(k);
            if (k > 0) KHRPushDescriptor
                .vkCmdPushDescriptorSetKHR(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, p.pipelineLayout, 0, w);
        }
        System.arraycopy(pushing, 0, pushed, 0, n);
        pushedLength = n;
        pushedProgram = p;
        pushedLayout = p.pipelineLayout;
    }

    /** Adds {sampler, image view} for a sampler uniform to {@link #pushing}. */
    private int textureEntry(int n, GLResources.Ctx c, VulkanShaders.Uniform u) {
        int unit = u == null ? 0 : Math.max(0, Math.min(GLResources.UNITS - 1, u.unit));
        GLResources.GLTexture tex = res.textures.get(c.boundTextures[unit]);
        if (tex == null || tex.image == null) tex = blank();
        pushing[n] = sampler(tex);
        pushing[n + 1] = tex.image.view;
        return n + 2;
    }

    /** Adds {buffer, offset, size} for a value block to {@link #pushing} (zeros when the stage has none). */
    private int valuesEntry(int n, GLResources.Ctx c, VulkanShaders.GLProgram p, boolean vertex) {
        int size = vertex ? p.vsUboSize : p.fsUboSize;
        pushing[n] = pushing[n + 1] = pushing[n + 2] = 0;
        if (size <= 0) return n + 3;
        if (p.externalUboBinding >= 0 && p.externalUboBinding < c.uboBuffer.length
            && c.uboBuffer[p.externalUboBinding] != 0) {
            GLResources.GLBuffer b = res.buffers.get(c.uboBuffer[p.externalUboBinding]);
            if (b == null || b.vk == null) return n + 3;
            b.vk.lastUse = dev.currentFrame();
            long offset = c.uboOffset[p.externalUboBinding];
            pushing[n] = b.vk.buffer;
            pushing[n + 1] = offset;
            pushing[n + 2] = Math.min(size, b.vk.size - offset);
            return n + 3;
        }
        java.nio.ByteBuffer v = vertex ? p.vsValues : p.fsValues;
        if (v == null) return n + 3;
        long at = vertex ? p.vsRingOffset : p.fsRingOffset;
        if (p.valuesDirty || at < 0) {
            at = ringWrite(v, size);
            if (vertex) p.vsRingOffset = at;
            else p.fsRingOffset = at;
            p.ringBuffer = ring.buffer;
        }
        pushing[n] = p.ringBuffer;
        pushing[n + 1] = at;
        pushing[n + 2] = size;
        return n + 3;
    }

    private int imageWrite(VkWriteDescriptorSet.Buffer w, int k, int binding, int e, MemoryStack stack) {
        VkDescriptorImageInfo.Buffer image = VkDescriptorImageInfo.calloc(1, stack);
        image.get(0)
            .sampler(pushing[e])
            .imageView(pushing[e + 1])
            .imageLayout(VK_IMAGE_LAYOUT_GENERAL);
        w.get(k)
            .sType$Default()
            .dstBinding(binding)
            .descriptorCount(1)
            .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .pImageInfo(image);
        return k + 1;
    }

    private int bufferWrite(VkWriteDescriptorSet.Buffer w, int k, int binding, int e, MemoryStack stack) {
        VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
        info.get(0)
            .buffer(pushing[e])
            .offset(pushing[e + 1])
            .range(pushing[e + 2]);
        w.get(k)
            .sType$Default()
            .dstBinding(binding)
            .descriptorCount(1)
            .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
            .pBufferInfo(info);
        return k + 1;
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
        if (t.vkSamplerVersion == t.samplerVersion && t.vkSamplerImage == t.image && t.vkSampler != 0)
            return t.vkSampler;
        long made = makeSampler(t);
        t.vkSampler = made;
        t.vkSamplerVersion = t.samplerVersion;
        t.vkSamplerImage = t.image;
        return made;
    }

    private long makeSampler(GLResources.GLTexture t) {
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
        int hash;

        PipelineKey(long[] parts) {
            this.parts = parts;
            this.hash = Arrays.hashCode(parts);
        }

        /** For the reusable lookup key, after its parts were filled in. */
        void rehash() {
            hash = Arrays.hashCode(parts);
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

    private final long[] keyScratch = new long[24];
    private final PipelineKey probe = new PipelineKey(keyScratch);
    private long lastPipeline;
    private final long[] lastKey = new long[24];

    private long pipeline(VulkanShaders.GLProgram p, VertexArray vao, RenderState s, int mode) {
        long[] k = keyScratch;
        Arrays.fill(k, 0);
        k[0] = (long) p.id << 32 | p.version;
        long inputs = 0;
        int j = 0;
        for (int loc : p.locations) {
            long fmt = vao.enabled[loc]
                ? vertexFormat(vao.type[loc], vao.size[loc], vao.normalized[loc], vao.integer[loc])
                : VK_FORMAT_R32G32B32A32_SFLOAT;
            long rate = vao.enabled[loc] && vao.divisor[loc] != 0 ? 1 : 0;
            k[1 + (j++ % 16)] ^= (loc + 1L) << 40 | fmt << 8 | rate;
            inputs |= 1L << loc;
        }
        k[17] = inputs;
        k[18] = (s.blend ? 1L : 0) | (long) s.srcRGB << 1
            | (long) s.dstRGB << 15
            | (long) s.srcAlpha << 29
            | (long) s.dstAlpha << 43;
        k[19] = (long) s.eqRGB << 32 | s.eqAlpha;
        k[20] = (s.colorMask[0] ? 1 : 0) | (s.colorMask[1] ? 2 : 0)
            | (s.colorMask[2] ? 4 : 0)
            | (s.colorMask[3] ? 8 : 0)
            | (long) topologyClass(mode) << 4
            | (long) s.polygonMode << 8;
        long formats = targetDepthFormat;
        for (int f : targetColorFormats) formats = formats * 1000003L + f;
        k[21] = formats;
        k[22] = targetColorFormats.length;
        // Most draws use the same pipeline as the draw before them.
        if (lastPipeline != 0 && Arrays.equals(k, lastKey)) return lastPipeline;
        probe.rehash();
        Long cached = pipelines.get(probe);
        long result;
        if (cached != null) result = cached;
        else {
            if (Stats.ON) Stats.fPipelines++;
            long t0 = Stats.ON ? System.nanoTime() : 0;
            result = createPipeline(p, vao, s, mode);
            if (Stats.ON) {
                Stats.fPipelineNs += System.nanoTime() - t0;
                Stats.fWhat += " pipeline(program " + p.id + ")";
            }
            pipelines.put(new PipelineKey(k.clone()), result);
        }
        System.arraycopy(k, 0, lastKey, 0, k.length);
        lastPipeline = result;
        return result;
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
    /** The image framebuffer 0 is drawn into this frame: the window's own (flipped) or the screen image. */
    /**
     * When Minecraft copies its framebuffer to the screen (Framebuffer.framebufferRender), the copy is not drawn:
     * this frame's screen simply is that framebuffer's image, and the window is filled from it directly. Anything
     * that draws into or reads the screen later in the frame first gets the real copy (see {@link #materializeScreen}).
     */
    private VulkanImage screenSource;
    /** Set while framebufferRender runs: its full-screen draw into the screen is then left out. */
    private boolean droppingScreenCopy;

    /** Called by the main mod before Minecraft copies its framebuffer (texture) to the screen. */
    boolean screenFromTexture(int texture, int framebuffer) {
        synchronized (res.lock) {
            res.ensureScreen();
            GLResources.GLTexture tex = res.textures.get(texture);
            if (tex == null || tex.image == null || tex.image.depth || dev.directTarget() != null) return false;
            screenSource = tex.image;
            droppingScreenCopy = true;
            // From the next frame on, this framebuffer is drawn top-down. (This frame is already drawn; the present
            // still flips it.) The size may differ from the window's for a moment while it is resized: the present
            // then scales.
            int owner = framebufferWithColor(texture);
            if (owner > 0 && owner != flippedFramebuffer) pendingFlippedFramebuffer = owner;
            // The image itself turns top-down from the next frame (it may get its picture by a copy rather than by
            // drawing; copies into a top-down image flip by themselves).
            if (!tex.image.topDown) pendingTopDownImage = tex.image;
            return true;
        }
    }

    /**
     * Minecraft's main framebuffer, which is drawn with its rows top-down (-1: none yet). Nothing samples it as a
     * texture except the end-of-frame copy that is replaced by the present, and copies of it (blits, captures) know
     * its row order from {@link VulkanImage#topDown}.
     */
    private int flippedFramebuffer = -1, pendingFlippedFramebuffer = -1;

    /** The framebuffer drawing into this texture as its first colour attachment, or -1. */
    private int framebufferWithColor(int texture) {
        int found = -1;
        for (int id = 1; id < framebufferIdLimit; id++) {
            Framebuffer fb = framebuffers.get(id);
            if (fb != null && fb.colorTexture[0] == texture) {
                if (found > 0) return -1; // more than one: leave it alone
                found = id;
            }
        }
        return found;
    }

    /** Above every framebuffer id handed out so far (they are small numbers). */
    int framebufferIdLimit = 1;

    /** Called after a frame was shown: a framebuffer that becomes top-down does so between frames. */
    private VulkanImage pendingTopDownImage;
    private int materializeLogs;

    void frameShown() {
        if (pendingTopDownImage != null) {
            endRendering();
            pendingTopDownImage.topDown = true;
            if (Stats.ON) LOG.info("image {} is now top-down", Long.toHexString(pendingTopDownImage.image));
            pendingTopDownImage = null;
            framebufferEdits++;
        }
        if (pendingFlippedFramebuffer < 0) return;
        endRendering();
        flippedFramebuffer = pendingFlippedFramebuffer;
        LOG.info("Framebuffer {} is now drawn top-down", flippedFramebuffer);
        pendingFlippedFramebuffer = -1;
        framebufferEdits++; // the target's row order changed
    }

    /** Called by the main mod after Minecraft's framebuffer copy. */
    void screenCopyDone() {
        droppingScreenCopy = false;
    }

    // A whole-screen clear not done yet (see clearLocked): which parts, and the values
    private int pendingScreenClear;
    private final float[] pendingClearColor = new float[4];
    private float pendingClearDepth = 1f;
    private int pendingClearStencil;

    /** Does a kept-back screen clear now, before something draws on or reads the screen. */
    private void applyScreenClear() {
        int m = pendingScreenClear;
        if (m == 0) return;
        pendingScreenClear = 0;
        res.ensureScreen();
        res.clearImages(
            res.screenColor,
            res.screenDepth,
            (m & 0x4000) != 0,
            pendingClearColor.clone(),
            (m & 0x0100) != 0,
            pendingClearDepth,
            (m & 0x0400) != 0,
            pendingClearStencil);
    }

    /** Makes the screen image hold the framebuffer copy for real, before anything else touches the screen. */
    private void materializeScreen() {
        VulkanImage source = screenSource;
        if (source == null || droppingScreenCopy) return;
        applyScreenClear(); // its depth part still matters; the colour is covered by the copy below
        screenSource = null;
        if (Stats.ON && materializeLogs++ < 3) LOG.info("screen copy made real", new Throwable("by"));
        blitImage(
            source,
            0,
            res.screenColor,
            0,
            VK_IMAGE_ASPECT_COLOR_BIT,
            0,
            0,
            source.width,
            source.height,
            0,
            0,
            source.width,
            source.height,
            VK_FILTER_NEAREST);
    }

    /** The image to show this frame, after which the screen goes back to normal. */
    VulkanImage takeShownScreen() {
        if (screenSource != null) pendingScreenClear = 0; // covered by the framebuffer, never seen
        else applyScreenClear();
        VulkanImage shown = screenSource != null ? screenSource : res.screenColor;
        screenSource = null;
        droppingScreenCopy = false;
        return shown;
    }

    private VulkanImage screenImage() {
        res.ensureScreen();
        if (screenSource != null) return screenSource;
        VulkanImage window = dev.directTarget();
        return window != null && window.width == res.screenDepth.width && window.height == res.screenDepth.height
            ? window
            : res.screenColor;
    }

    private VulkanImage readColor(int framebuffer, int[] level) {
        level[0] = 0;
        if (framebuffer == 0) {
            materializeScreen();
            applyScreenClear();
            applyScreenClear();
            return screenImage();
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
            materializeScreen();
            VulkanImage window = dev.acquireTarget();
            return window != null && window.width == res.screenDepth.width && window.height == res.screenDepth.height
                ? window
                : res.screenColor;
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
        // The window's image (and Minecraft's main framebuffer) have their rows top-down; OpenGL's coordinates
        // count from the bottom.
        if (src.topDown || src == dev.directTarget()) {
            sy0 = sh - sy0;
            sy1 = sh - sy1;
        }
        if (dst.topDown || dst == dev.directTarget()) {
            dy0 = dh - dy0;
            dy1 = dh - dy1;
        }
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

    /** At shutdown: frees pipelines, samplers, the per-frame rings and renderbuffer images. */
    void freeAll() {
        synchronized (res.lock) {
            endRendering();
            for (long pipeline : pipelines.values()) if (pipeline != 0) vkDestroyPipeline(dev.vk(), pipeline, null);
            pipelines.clear();
            for (long sampler : samplers.values()) vkDestroySampler(dev.vk(), sampler, null);
            samplers.clear();
            for (int i = 0; i < rings.length; i++) {
                if (rings[i] != null) rings[i].release(dev);
                rings[i] = null;
            }
            for (Renderbuffer rb : renderbuffers.values()) if (rb.image != null) {
                rb.image.release(dev);
                rb.image = null;
            }
            if (blank != null && blank.image != null) blank.image.release(dev);
            blank = null;
        }
    }

    // ------------------------------------------------------------------ reading pixels back (screenshots)

    /**
     * Copies a rectangle of a colour image to {@code dst} in OpenGL's layout: rows bottom-up, {@code glFormat} RGBA,
     * BGRA, RGB or BGR with 8-bit channels (GL_UNSIGNED_BYTE or the packed 8_8_8_8(_REV) types), rows packed with the
     * given alignment. Waits for the graphics card (used for screenshots, not per frame).
     */
    boolean readImage(VulkanImage img, int level, int x, int y, int w, int h, int glFormat, int glType, long dst,
        int alignment) {
        if (img == null || img.depth || w <= 0 || h <= 0 || dst == 0) return false;
        int lw = Math.max(1, img.width >> level), lh = Math.max(1, img.height >> level);
        x = clamp(x, 0, lw);
        y = clamp(y, 0, lh);
        w = Math.min(w, lw - x);
        h = Math.min(h, lh - y);
        if (w <= 0 || h <= 0) return false;
        boolean bgr = glFormat == 0x80E1 || glFormat == 0x80E0; // BGRA, BGR
        int outTexel = glFormat == 0x1907 || glFormat == 0x80E0 ? 3 : 4; // RGB, BGR
        if (!(glFormat == 0x1908 || glFormat == 0x80E1 || glFormat == 0x1907 || glFormat == 0x80E0)) return false;
        boolean reversed = glType == 0x8035; // UNSIGNED_INT_8_8_8_8 (not _REV): bytes come out as A,B,G,R
        boolean bgraImage = img.format == VK_FORMAT_B8G8R8A8_UNORM;
        if (img.format != VK_FORMAT_R8G8B8A8_UNORM && !bgraImage) return false;
        // Rows of the image in memory: bottom-up for OpenGL's own images, top-down for the window-ordered ones.
        int memY = img.topDown ? lh - (y + h) : y;
        VulkanBuffer buffer;
        synchronized (res.lock) {
            endRendering();
            res.flushUploads();
            buffer = new VulkanBuffer(dev, (long) w * h * 4);
            VkCommandBuffer cmd = dev.cmd();
            Barriers.full(cmd);
            try (MemoryStack stack = stackPush()) {
                org.lwjgl.vulkan.VkBufferImageCopy.Buffer region = org.lwjgl.vulkan.VkBufferImageCopy.calloc(1, stack);
                final int mip = level;
                region.get(0)
                    .imageSubresource(
                        r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .mipLevel(mip)
                            .layerCount(1));
                region.get(0)
                    .imageOffset()
                    .set(x, memY, 0);
                region.get(0)
                    .imageExtent()
                    .set(w, h, 1);
                vkCmdCopyImageToBuffer(cmd, img.image, VK_IMAGE_LAYOUT_GENERAL, buffer.buffer, region);
            }
            Barriers.full(cmd);
            dev.flushAndWait();
        }
        try {
            long rowOut = (long) w * outTexel;
            if (alignment > 1) rowOut = (rowOut + alignment - 1) / alignment * alignment;
            for (int row = 0; row < h; row++) {
                // OpenGL row 'row' (counted from the bottom of the rectangle)
                int srcRow = img.topDown ? h - 1 - row : row;
                long src = buffer.address + (long) srcRow * w * 4;
                long out = dst + row * rowOut;
                for (int i = 0; i < w; i++) {
                    int p = MemoryUtil.memGetInt(src + i * 4L); // bytes in image order
                    int c0 = p & 0xFF, c1 = (p >> 8) & 0xFF, c2 = (p >> 16) & 0xFF, a = (p >>> 24);
                    int r = bgraImage ? c2 : c0, g = c1, b = bgraImage ? c0 : c2;
                    long o = out + (long) i * outTexel;
                    int first = bgr ? b : r, third = bgr ? r : b;
                    if (outTexel == 4 && reversed) {
                        MemoryUtil.memPutByte(o, (byte) a);
                        MemoryUtil.memPutByte(o + 1, (byte) third);
                        MemoryUtil.memPutByte(o + 2, (byte) g);
                        MemoryUtil.memPutByte(o + 3, (byte) first);
                    } else {
                        MemoryUtil.memPutByte(o, (byte) first);
                        MemoryUtil.memPutByte(o + 1, (byte) g);
                        MemoryUtil.memPutByte(o + 2, (byte) third);
                        if (outTexel == 4) MemoryUtil.memPutByte(o + 3, (byte) a);
                    }
                }
            }
        } finally {
            buffer.release(dev);
        }
        return true;
    }

    /** The colour image glReadPixels reads: the read framebuffer's read buffer (the screen for framebuffer 0). */
    VulkanImage readSource(GLResources.Ctx c, int[] level) {
        synchronized (res.lock) {
            if (c.readFramebuffer == 0 && screenSource != null) {
                level[0] = 0;
                return screenSource;
            }
            return readColor(c.readFramebuffer, level);
        }
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
    private boolean shotTopDown, shotBgra;

    /** Development aid (CLIENTM_VK_SHOT=frame,frame...): records a copy of the screen image of those frames. */
    private static final java.util.Set<Long> BENCH_SHOTS = new java.util.HashSet<>();
    static {
        String v = System.getenv("CLIENTM_VK_SHOT_BENCH");
        if (v != null) for (String part : v.split(",")) {
            try {
                BENCH_SHOTS.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {}
        }
    }
    private long benchStart = -1;

    void captureBeforePresent() {
        long frame = dev.currentFrame();
        boolean benching = System.getProperty("clientm.benching") != null;
        if (benching && benchStart < 0) benchStart = frame;
        boolean benchShot = benching && BENCH_SHOTS.contains(frame - benchStart);
        if (!SHOTS.contains(frame) && !benchShot || res.screenColor == null) return;
        VulkanImage img = screenSource != null ? screenSource : screenImage();
        shotTopDown = img.topDown || img == dev.directTarget();
        shotBgra = img.format == VK_FORMAT_B8G8R8A8_UNORM;
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
                if (shotBgra) {
                    int t = r;
                    r = b;
                    b = t;
                }
                out.setRGB(x, shotTopDown ? y : shotHeight - 1 - y, r << 16 | g << 8 | b);
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
