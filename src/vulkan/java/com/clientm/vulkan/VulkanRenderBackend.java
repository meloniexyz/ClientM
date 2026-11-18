package com.clientm.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.glsm.backend.ScreenCapture;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * ClientM's Vulkan render backend for Angelica (stage 1).
 *
 * Angelica sends every drawing command of the game through one render backend. This one owns the window through
 * Vulkan. At this stage it does not draw yet: commands are answered the way a working driver would (new ids, shaders
 * compiled, framebuffers complete) so the game keeps running, and every frame shows the colour the game last cleared
 * the screen to. Drawing is added in the next stages.
 *
 * The method bodies for the commands that do nothing yet are generated from Angelica's backend contract.
 */
// Calls LWJGL 3 directly, so lwjgl3ify must not redirect it to its LWJGL 2 compatibility layer.
@Lwjgl3Aware
public final class VulkanRenderBackend extends RenderBackend {

    private static final Logger LOG = LogManager.getLogger("ClientM/Vulkan");

    private static final int GL_VENDOR = 0x1F00, GL_RENDERER = 0x1F01, GL_VERSION = 0x1F02, GL_EXTENSIONS = 0x1F03,
        GL_SHADING_LANGUAGE_VERSION = 0x8B8C;
    private static final int GL_FRAMEBUFFER = 0x8D40, GL_DRAW_FRAMEBUFFER = 0x8CA9, GL_COLOR_BUFFER_BIT = 0x4000;

    private int nextId = 1;
    private long nextSync = 1;
    private float frameR, frameG, frameB;
    private List<String> extensions = new ArrayList<>();
    private final Map<Integer, Integer> boundBuffers = new HashMap<>();
    private final Map<Integer, Long> sizes = new HashMap<>();
    private final Map<Integer, ByteBuffer> mapped = new HashMap<>();
    private final Map<String, Integer> locations = new HashMap<>();

    private int nextId() {
        return nextId++;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public boolean isAvailable() {
        return VulkanGate.isEngaged();
    }

    @Override
    public int getPriority() {
        return 300;
    }

    @Override
    public String getName() {
        VulkanDevice d = VulkanGate.device();
        return "Vulkan (ClientM, " + (d == null ? "not started" : d.gpuName()) + ")";
    }

    @Override
    public void onPostWindowCreate(long window) {
        advertise();
    }

    @Override
    public void init() {
        advertise();
        LOG.info(
            "ClientM Vulkan backend started (stage 2 in progress: textures and buffers are real, drawing is not done yet)");
    }

    /**
     * Mods and Angelica ask which OpenGL features exist before drawing. The same list Angelica's own SDL GPU backend
     * reports is used, since this backend answers the same commands.
     */
    private void advertise() {
        try {
            Set<String> caps = com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend.advertisedCapabilities();
            me.eigenraven.lwjgl3ify.api.GLCapabilitiesOverride.set(caps);
            com.gtnewhorizons.angelica.sdlgpu.compat.Lwjgl3GLCapabilitiesShim.installFunctionProvider();
            com.gtnewhorizons.angelica.sdlgpu.compat.Lwjgl3GLCapabilitiesShim.installOnCurrentThread(caps);
            List<String> ext = new ArrayList<>();
            for (String c : caps) if (c.startsWith("GL_")) ext.add(c);
            extensions = ext;
        } catch (Throwable t) {
            LOG.warn("Could not publish the OpenGL feature list ({})", t.toString());
        }
    }

    @Override
    public void shutdown() {
        VulkanDevice d = VulkanGate.device();
        if (d != null) d.destroy();
    }

    @Override
    public boolean hasContext() {
        return VulkanGate.isEngaged();
    }

    @Override
    public boolean isCurrent() {
        return VulkanGate.isEngaged();
    }

    @Override
    public int getMinGLSLVersion() {
        return 330;
    }

    @Override
    protected VSyncMode applyVSyncMode(VSyncMode preferred) {
        return preferred;
    }

    // ------------------------------------------------------------------ frames

    /**
     * With Vulkan there are no OpenGL contexts at all, so switching to one (Forge's loading screen does this on its own
     * thread) only needs the feature list on that thread.
     */
    @Override
    public boolean handleMakeCurrent(Object drawable) {
        if (!VulkanGate.isEngaged()) return false;
        try {
            com.gtnewhorizons.angelica.sdlgpu.compat.Lwjgl3GLCapabilitiesShim
                .installOnCurrentThread(com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend.advertisedCapabilities());
        } catch (Throwable ignored) {}
        return true;
    }

    @Override
    public boolean handleReleaseContext(Object drawable) {
        return VulkanGate.isEngaged();
    }

    @Override
    public void clearColor(float red, float green, float blue, float alpha) {
        res().ctx().clearR = red;
        res().ctx().clearG = green;
        res().ctx().clearB = blue;
        res().ctx().clearA = alpha;
    }

    @Override
    public void bindFramebuffer(int target, int framebuffer) {
        if (target == GL_FRAMEBUFFER || target == GL_DRAW_FRAMEBUFFER) res().ctx().drawFramebuffer = framebuffer;
    }

    // ------------------------------------------------------------------ questions the game asks

    @Override
    public String getString(int pname) {
        switch (pname) {
            case GL_VENDOR:
                return "ClientM";
            case GL_RENDERER:
                return getName();
            case GL_VERSION:
                return "4.6 (ClientM Vulkan)";
            case GL_SHADING_LANGUAGE_VERSION:
                return "4.60";
            case GL_EXTENSIONS:
                return String.join(" ", extensions);
            default:
                return "";
        }
    }

    @Override
    public String getStringi(int name, int index) {
        return index >= 0 && index < extensions.size() ? extensions.get(index) : "";
    }

    @Override
    public int getInteger(int pname) {
        switch (pname) {
            case 0x0D33: // MAX_TEXTURE_SIZE
            case 0x84E8: // MAX_RENDERBUFFER_SIZE
                return 16384;
            case 0x8073: // MAX_3D_TEXTURE_SIZE
            case 0x88FF: // MAX_ARRAY_TEXTURE_LAYERS
                return 2048;
            case 0x8872: // MAX_TEXTURE_IMAGE_UNITS
                return 32;
            case 0x8B4D: // MAX_COMBINED_TEXTURE_IMAGE_UNITS
                return 32;
            case 0x84E2: // MAX_TEXTURE_UNITS
                return 8;
            case 0x8869: // MAX_VERTEX_ATTRIBS
                return 16;
            case 0x8824: // MAX_DRAW_BUFFERS
            case 0x8CDF: // MAX_COLOR_ATTACHMENTS
            case 0x8D57: // MAX_SAMPLES
                return 8;
            case 0x8A2F: // MAX_UNIFORM_BUFFER_BINDINGS
                return 36;
            case 0x8A30: // MAX_UNIFORM_BLOCK_SIZE
                return 65536;
            case 0x8A34: // UNIFORM_BUFFER_OFFSET_ALIGNMENT
                return 256;
            case 0x90DD: // MAX_SHADER_STORAGE_BUFFER_BINDINGS
                return 16;
            case 0x821B: // MAJOR_VERSION
                return 4;
            case 0x821C: // MINOR_VERSION
                return 6;
            case 0x821D: // NUM_EXTENSIONS
                return extensions.size();
            case 0x9126: // CONTEXT_PROFILE_MASK: a core profile, which turns on Angelica's shader path
                return 0x1;
            case 0x0D32: // MAX_CLIP_DISTANCES
            case 0x8F38: // MAX_IMAGE_UNITS
                return 8;
            case 0x8B4C: // MAX_VERTEX_TEXTURE_IMAGE_UNITS
                return 32;
            case 0x851C: // MAX_CUBE_MAP_TEXTURE_SIZE
                return 16384;
            case 0x8B49: // MAX_FRAGMENT_UNIFORM_COMPONENTS
            case 0x8B4A: // MAX_VERTEX_UNIFORM_COMPONENTS
                return 4096;
            case 0x826C: // MAX_DEBUG_GROUP_STACK_DEPTH
                return 64;
            case 0x82E8: // MAX_LABEL_LENGTH
                return 256;
            case 0x9143: // MAX_DEBUG_MESSAGE_LENGTH
                return 1024;
            case 0x0D56: // DEPTH_BITS
                return 24;
            case 0x0D57: // STENCIL_BITS
                return 8;
            case 0x0D05: // PACK_ALIGNMENT
            case 0x0CF5: // UNPACK_ALIGNMENT
                return 4;
            default:
                return 0;
        }
    }

    @Override
    public void getInteger(int pname, IntBuffer params) {
        params.put(params.position(), getInteger(pname));
    }

    @Override
    public int getIntegerIndexed(int pname, int index) {
        return 0;
    }

    @Override
    public float getFloat(int pname) {
        return pname == 0x84FF ? 16f : 0f; // MAX_TEXTURE_MAX_ANISOTROPY
    }

    @Override
    public void getFloat(int pname, FloatBuffer params) {
        params.put(params.position(), getFloat(pname));
    }

    @Override
    public boolean getBoolean(int pname) {
        return false;
    }

    @Override
    public void getBoolean(int pname, ByteBuffer params) {
        params.put(params.position(), (byte) 0);
    }

    @Override
    public int getError() {
        return 0;
    }

    @Override
    public int checkFramebufferStatus(int target) {
        return 0x8CD5; // FRAMEBUFFER_COMPLETE
    }

    @Override
    public int getShaderi(int shader, int pname) {
        return pname == 0x8B81 ? 1 : 0; // COMPILE_STATUS
    }

    @Override
    public int getProgrami(int program, int pname) {
        return pname == 0x8B82 || pname == 0x8B83 ? 1 : 0; // LINK_STATUS, VALIDATE_STATUS
    }

    @Override
    public String getShaderInfoLog(int shader, int maxLength) {
        return "";
    }

    @Override
    public String getProgramInfoLog(int program, int maxLength) {
        return "";
    }

    @Override
    public void getShaderInfoLog(int shader, IntBuffer length, ByteBuffer infoLog) {
        if (length != null) length.put(length.position(), 0);
    }

    @Override
    public void getProgramInfoLog(int program, IntBuffer length, ByteBuffer infoLog) {
        if (length != null) length.put(length.position(), 0);
    }

    /** Every uniform or attribute name gets a stable, distinct location per program. */
    private int location(int program, String name) {
        String key = program + "/" + name;
        Integer known = locations.get(key);
        if (known == null) {
            known = locations.size();
            locations.put(key, known);
        }
        return known;
    }

    private static String text(ByteBuffer name) {
        byte[] bytes = new byte[name.remaining()];
        name.duplicate()
            .get(bytes);
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) end++;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    @Override
    public int getUniformLocation(int program, CharSequence name) {
        return location(program, "u:" + name);
    }

    @Override
    public int getUniformLocation(int program, ByteBuffer name) {
        return location(program, "u:" + text(name));
    }

    @Override
    public int getAttribLocation(int program, CharSequence name) {
        return location(program, "a:" + name) % 16;
    }

    @Override
    public int getAttribLocation(int program, ByteBuffer name) {
        return location(program, "a:" + text(name)) % 16;
    }

    @Override
    public int getUniformBlockIndex(int program, CharSequence name) {
        return 0;
    }

    // Optional commands that the base contract refuses unless a backend implements them. Until the GPU work is real,
    // everything counts as finished and visible.

    @Override
    public void polygonOffsetClamp(float factor, float units, float clamp) {}

    @Override
    public void multiDrawArraysIndirectCount(int mode, long indirect, long drawcount, int maxdrawcount, int stride) {}

    @Override
    public void multiDrawElementsIndirectCount(int mode, int type, long indirect, long drawcount, int maxdrawcount,
        int stride) {}

    @Override
    public void genQueries(IntBuffer ids) {
        while (ids.hasRemaining()) ids.put(nextId());
    }

    @Override
    public int genQueries() {
        return nextId();
    }

    @Override
    public void deleteQueries(int id) {}

    @Override
    public void beginQuery(int target, int id) {}

    @Override
    public void endQuery(int target) {}

    @Override
    public void getQueryObjectui(int id, int pname, IntBuffer params) {
        params.put(params.position(), 1);
    }

    @Override
    public int getQueryObjecti(int id, int pname) {
        return 1;
    }

    // ------------------------------------------------------------------ buffers that the game writes into

    private void sizeBound(int target, long size) {
        Integer buffer = boundBuffers.get(target);
        if (buffer != null) sizes.put(buffer, size);
    }

    /** Memory for the game to write into; until drawing exists, what is written is simply kept here. */
    private ByteBuffer memory(int target, long length) {
        Integer buffer = boundBuffers.get(target);
        int key = buffer == null ? -target : buffer;
        int size = (int) Math.max(16, Math.min(Integer.MAX_VALUE, length));
        ByteBuffer b = mapped.get(key);
        if (b == null || b.capacity() < size) {
            b = ByteBuffer.allocateDirect(size)
                .order(ByteOrder.nativeOrder());
            mapped.put(key, b);
        }
        b.clear();
        b.limit(size);
        return b;
    }

    @Override
    public ScreenCapture createScreenCapture(int width, int height) {
        return null;
    }

    // ================================================================== stage 2: frames, buffers, textures, syncs

    private int proxyWidth, proxyHeight;
    private int texImageNoTexture;
    private int texLog;
    private int misfitLogs;
    private final java.util.ArrayDeque<String> recentBinds = new java.util.ArrayDeque<>();

    private void bindLog(String event) {
        if (!DEBUG_TEX) return;
        if (recentBinds.size() >= 12) recentBinds.removeFirst();
        recentBinds.addLast(event);
    }

    private static final boolean DEBUG_TEX = System.getenv("CLIENTM_VK_DEBUG_TEX") != null;

    private volatile GLResources res;

    /** Created once; the loading screen's thread and the main thread both reach it at start-up. */
    private GLResources res() {
        GLResources r = res;
        if (r != null) return r;
        synchronized (this) {
            if (res == null) res = new GLResources(VulkanGate.device());
            return res;
        }
    }

    /** Angelica calls this once per game frame, after the game has drawn it: the frame is shown. */
    @Override
    public void onFrameEnd() {
        GLResources r = res();
        synchronized (r.lock) {
            r.ensureScreen();
            r.dev.endFrame(r.screenColor);
        }
    }

    /** Forge's loading screen presents from its own thread with a buffer swap. */
    @Override
    public boolean handleSwapBuffers() {
        if (Thread.currentThread() != com.gtnewhorizons.angelica.glsm.GLStateManager.getMainThread()
            && !isPresentSuppressed()) {
            onFrameEnd();
        }
        return true;
    }

    @Override
    public void clearDepth(double depth) {
        res().ctx().clearDepth = (float) depth;
    }

    @Override
    public void clearStencil(int s) {
        res().ctx().clearStencil = s;
    }

    @Override
    public void clear(int mask) {
        if (res().ctx().drawFramebuffer != 0) return; // framebuffers come with drawing (next step)
        GLResources r = res();
        r.ensureScreen();
        r.clearImages(
            r.screenColor,
            r.screenDepth,
            (mask & 0x4000) != 0,
            new float[] { res().ctx().clearR, res().ctx().clearG, res().ctx().clearB, res().ctx().clearA },
            (mask & 0x100) != 0,
            res().ctx().clearDepth,
            (mask & 0x400) != 0,
            res().ctx().clearStencil);
    }

    // ------------------------------------------------------------------ buffers

    @Override
    public int genBuffers() {
        int id = nextId();
        res().buffer(id);
        return id;
    }

    @Override
    public int createBuffers() {
        return genBuffers();
    }

    @Override
    public void deleteBuffers(int buffer) {
        GLResources r = res();
        GLResources.GLBuffer b = r.buffers.remove(buffer);
        if (b != null && b.vk != null) synchronized (r.lock) {
            b.vk.release(r.dev);
        }
        for (int i = 0; i < r.ctx().bound.length; i++) if (r.ctx().bound[i] == buffer) r.ctx().bound[i] = 0;
    }

    @Override
    public void deleteBuffers(IntBuffer buffers) {
        for (int i = buffers.position(); i < buffers.limit(); i++) deleteBuffers(buffers.get(i));
    }

    @Override
    public boolean isBuffer(int buffer) {
        return res().buffers.containsKey(buffer);
    }

    @Override
    public void bindBuffer(int target, int buffer) {
        GLResources r = res();
        r.ctx().bound[GLResources.slot(target)] = buffer;
        if (buffer != 0) r.buffer(buffer);
    }

    private static ByteBuffer bytes(java.nio.Buffer data) {
        if (data == null) return null;
        if (data instanceof ByteBuffer) return (ByteBuffer) data;
        int size = data instanceof FloatBuffer || data instanceof IntBuffer ? 4
            : data instanceof DoubleBuffer ? 8 : data instanceof ShortBuffer ? 2 : 1;
        return org.lwjgl.system.MemoryUtil
            .memByteBuffer(org.lwjgl.system.MemoryUtil.memAddress(data), data.remaining() * size);
    }

    private void data(GLResources.GLBuffer b, long size, java.nio.Buffer data) {
        ByteBuffer bb = bytes(data);
        res().allocate(b, data == null ? size : bb.remaining(), bb, false);
    }

    @Override
    public void bufferData(int target, long size, int usage) {
        data(res().bufferAt(target), size, null);
    }

    @Override
    public void bufferData(int target, ByteBuffer data, int usage) {
        data(res().bufferAt(target), 0, data);
    }

    @Override
    public void bufferData(int target, FloatBuffer data, int usage) {
        data(res().bufferAt(target), 0, data);
    }

    @Override
    public void bufferData(int target, IntBuffer data, int usage) {
        data(res().bufferAt(target), 0, data);
    }

    @Override
    public void bufferData(int target, ShortBuffer data, int usage) {
        data(res().bufferAt(target), 0, data);
    }

    @Override
    public void bufferData(int target, DoubleBuffer data, int usage) {
        data(res().bufferAt(target), 0, data);
    }

    @Override
    public void bufferData(int target, int[] data, int usage) {
        IntBuffer copy = org.lwjgl.BufferUtils.createIntBuffer(data.length);
        copy.put(data)
            .flip();
        data(res().bufferAt(target), 0, copy);
    }

    @Override
    public void bufferData(int target, float[] data, int usage) {
        FloatBuffer copy = org.lwjgl.BufferUtils.createFloatBuffer(data.length);
        copy.put(data)
            .flip();
        data(res().bufferAt(target), 0, copy);
    }

    @Override
    public void bufferStorage(int target, long size, int flags) {
        res().allocate(res().bufferAt(target), size, null, true);
    }

    @Override
    public void bufferStorage(int target, ByteBuffer data, int flags) {
        res().allocate(res().bufferAt(target), data.remaining(), data, true);
    }

    @Override
    public void namedBufferData(int buffer, long size, int usage) {
        data(res().buffer(buffer), size, null);
    }

    @Override
    public void namedBufferData(int buffer, ByteBuffer data, int usage) {
        data(res().buffer(buffer), 0, data);
    }

    @Override
    public void namedBufferData(int buffer, FloatBuffer data, int usage) {
        data(res().buffer(buffer), 0, data);
    }

    @Override
    public void bufferSubData(int target, long offset, ByteBuffer data) {
        res().write(res().bufferAt(target), offset, data);
    }

    @Override
    public void bufferSubData(int target, long offset, FloatBuffer data) {
        res().write(res().bufferAt(target), offset, bytes(data));
    }

    @Override
    public void bufferSubData(int target, long offset, IntBuffer data) {
        res().write(res().bufferAt(target), offset, bytes(data));
    }

    @Override
    public void bufferSubData(int target, long offset, ShortBuffer data) {
        res().write(res().bufferAt(target), offset, bytes(data));
    }

    @Override
    public void bufferSubData(int target, long offset, DoubleBuffer data) {
        res().write(res().bufferAt(target), offset, bytes(data));
    }

    @Override
    public void namedBufferSubData(int buffer, long offset, ByteBuffer data) {
        res().write(res().buffer(buffer), offset, data);
    }

    @Override
    public void copyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size) {
        GLResources r = res();
        r.copy(r.bufferAt(readTarget), r.bufferAt(writeTarget), readOffset, writeOffset, size);
    }

    /**
     * Mapping hands out the buffer's memory directly. Unless the game says it does its own syncing (persistent or
     * unsynchronized mapping), OpenGL waits for the GPU here, and so does this.
     */
    private ByteBuffer map(GLResources.GLBuffer b, long offset, long length, int access) {
        if (b == null) return null;
        GLResources r = res();
        if (b.vk == null) r.allocate(b, Math.max(16, offset + length), null, false);
        boolean invalidate = (access & 0x0004) != 0 || (access & 0x0008) != 0; // INVALIDATE_RANGE / _BUFFER
        boolean unsynchronized = (access & 0x0020) != 0 || (access & 0x0040) != 0 || b.persistent;
        if (!unsynchronized && r.inUse(b.vk)) {
            if (invalidate && (access & 0x0008) != 0) r.allocate(b, b.vk.size, null, false);
            else r.dev.waitFor(b.vk.lastUse);
        }
        return b.vk.view(offset, length);
    }

    @Override
    public ByteBuffer mapBuffer(int target, int access) {
        GLResources.GLBuffer b = res().bufferAt(target);
        return b == null || b.vk == null ? null : map(b, 0, b.vk.size, 0);
    }

    @Override
    public ByteBuffer mapBuffer(int target, int access, long length, ByteBuffer old_buffer) {
        return map(res().bufferAt(target), 0, length, 0);
    }

    @Override
    public ByteBuffer mapBufferRange(int target, long offset, long length, int access) {
        return map(res().bufferAt(target), offset, length, access);
    }

    @Override
    public boolean unmapBuffer(int target) {
        return true;
    }

    @Override
    public void flushMappedBufferRange(int target, long offset, long length) {}

    private void readBack(int target, long offset, java.nio.Buffer data) {
        GLResources.GLBuffer b = res().bufferAt(target);
        if (b == null || b.vk == null) return;
        if (b.vk.lastUse != 0) res().dev.waitFor(b.vk.lastUse);
        ByteBuffer out = bytes(data);
        long n = Math.min(out.remaining(), b.vk.size - offset);
        org.lwjgl.system.MemoryUtil.memCopy(b.vk.address + offset, org.lwjgl.system.MemoryUtil.memAddress(out), n);
    }

    @Override
    public void getBufferSubData(int target, long offset, ByteBuffer data) {
        readBack(target, offset, data);
    }

    @Override
    public void getBufferSubData(int target, long offset, FloatBuffer data) {
        readBack(target, offset, data);
    }

    @Override
    public void getBufferSubData(int target, long offset, IntBuffer data) {
        readBack(target, offset, data);
    }

    @Override
    public void getBufferSubData(int target, long offset, ShortBuffer data) {
        readBack(target, offset, data);
    }

    @Override
    public void getBufferSubData(int target, long offset, DoubleBuffer data) {
        readBack(target, offset, data);
    }

    @Override
    public int getBufferParameteri(int target, int pname) {
        GLResources.GLBuffer b = res().bufferAt(target);
        if (pname == 0x8764) return b == null || b.vk == null ? 0 : (int) b.vk.size; // BUFFER_SIZE
        return 0;
    }

    // ------------------------------------------------------------------ textures

    @Override
    public int genTextures() {
        int id = nextId();
        res().texture(id);
        return id;
    }

    @Override
    public void genTextures(IntBuffer textures) {
        for (int i = textures.position(); i < textures.limit(); i++) textures.put(i, genTextures());
    }

    @Override
    public int createTextures(int target) {
        return genTextures();
    }

    @Override
    public void deleteTextures(int texture) {
        GLResources r = res();
        GLResources.GLTexture t = r.textures.remove(texture);
        if (t != null && t.image != null) synchronized (r.lock) {
            t.image.release(r.dev);
        }
        for (int i = 0; i < r.ctx().boundTextures.length; i++)
            if (r.ctx().boundTextures[i] == texture) r.ctx().boundTextures[i] = 0;
    }

    @Override
    public boolean isTexture(int texture) {
        return res().textures.containsKey(texture);
    }

    @Override
    public void activeTexture(int texture) {
        bindLog("active " + (texture - 0x84C0));
        res().ctx().activeUnit = Math.max(0, Math.min(GLResources.UNITS - 1, texture - 0x84C0));
    }

    @Override
    public void bindTexture(int target, int texture) {
        bindLog("bind 0x" + Integer.toHexString(target) + " " + texture);
        GLResources r = res();
        r.ctx().boundTextures[r.ctx().activeUnit] = texture;
        if (texture != 0) r.texture(texture);
    }

    @Override
    public void bindTextureUnit(int unit, int texture) {
        bindLog("unit " + unit + " " + texture);
        GLResources r = res();
        if (unit >= 0 && unit < GLResources.UNITS) r.ctx().boundTextures[unit] = texture;
        if (texture != 0) r.texture(texture);
    }

    @Override
    public void pixelStorei(int pname, int param) {
        GLResources r = res();
        switch (pname) {
            case 0x0CF2:
                r.ctx().unpackRowLength = param;
                break;
            case 0x0CF3:
                r.ctx().unpackSkipRows = param;
                break;
            case 0x0CF4:
                r.ctx().unpackSkipPixels = param;
                break;
            case 0x0CF5:
                r.ctx().unpackAlignment = param;
                break;
            default:
                break;
        }
    }

    private static final int GL_TEXTURE_2D = 0x0DE1, GL_PROXY_TEXTURE_2D = 0x8064;

    private void image2D(GLResources.GLTexture t, int target, int level, int internal, int width, int height,
        int format, int type, long pixels) {
        if (DEBUG_TEX && texLog++ < 100000) LOG.info(
            "image2D tex={} target=0x{} level={} {}x{} internal=0x{} unit={} pixels={}",
            t == null ? -1 : t.id,
            Integer.toHexString(target),
            level,
            width,
            height,
            Integer.toHexString(internal),
            res().ctx().activeUnit,
            pixels != 0);
        if (target == GL_PROXY_TEXTURE_2D) {
            proxyWidth = width;
            proxyHeight = height;
            return;
        }
        if (t == null || target != GL_TEXTURE_2D) {
            if (t == null && texImageNoTexture++ < 4)
                LOG.warn("texImage2D with no texture bound (unit {})", res().ctx().activeUnit);
            return;
        }
        GLResources r = res();
        if (level == 0) {
            r.define(t, internal, width, height, GLResources.fullChain(width, height, t.maxLevel));
        } else if (t.image == null) {
            r.define(t, internal, width << level, height << level, level + 1);
        }
        if (pixels != 0) r.upload(t, level, 0, 0, width, height, format, type, pixels);
    }

    private long unpackAddress(long offset) {
        GLResources.GLBuffer pbo = res().bufferAt(0x88EC);
        return pbo == null || pbo.vk == null ? 0 : pbo.vk.address + offset;
    }

    private static long address(java.nio.Buffer b) {
        return b == null ? 0 : org.lwjgl.system.MemoryUtil.memAddress(b);
    }

    @Override
    public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format,
        int type, ByteBuffer pixels) {
        image2D(res().boundTexture(), target, level, internalFormat, width, height, format, type, address(pixels));
    }

    @Override
    public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format,
        int type, IntBuffer pixels) {
        image2D(res().boundTexture(), target, level, internalFormat, width, height, format, type, address(pixels));
    }

    @Override
    public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format,
        int type, FloatBuffer pixels) {
        image2D(res().boundTexture(), target, level, internalFormat, width, height, format, type, address(pixels));
    }

    @Override
    public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format,
        int type, DoubleBuffer pixels) {
        image2D(res().boundTexture(), target, level, internalFormat, width, height, format, type, address(pixels));
    }

    @Override
    public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format,
        int type, long pixelBufferOffset) {
        image2D(
            res().boundTexture(),
            target,
            level,
            internalFormat,
            width,
            height,
            format,
            type,
            unpackAddress(pixelBufferOffset));
    }

    @Override
    public void textureImage2DEXT(int texture, int target, int level, int internalformat, int width, int height,
        int border, int format, int type, ByteBuffer pixels) {
        image2D(res().texture(texture), target, level, internalformat, width, height, format, type, address(pixels));
    }

    @Override
    public void textureImage2DEXT(int texture, int target, int level, int internalformat, int width, int height,
        int border, int format, int type, IntBuffer pixels) {
        image2D(res().texture(texture), target, level, internalformat, width, height, format, type, address(pixels));
    }

    @Override
    public void texStorage2D(int target, int levels, int internalFormat, int width, int height) {
        if (DEBUG_TEX) LOG.info(
            "texStorage2D target=0x{} levels={} {}x{} tex={}",
            Integer.toHexString(target),
            levels,
            width,
            height,
            res().boundTexture() == null ? -1 : res().boundTexture().id);
        GLResources.GLTexture t = res().boundTexture();
        if (t != null && target == GL_TEXTURE_2D) res().define(t, internalFormat, width, height, levels);
    }

    private void subImage(GLResources.GLTexture t, int level, int x, int y, int w, int h, int format, int type,
        long pixels) {
        if (DEBUG_TEX && t != null
            && t.image != null
            && (x + w > t.image.width || y + h > t.image.height)
            && misfitLogs++ < 3) LOG.info("misfit into tex {} after binds: {}", t.id, String.join(" | ", recentBinds));
        if (t != null && pixels != 0) res().upload(t, level, x, y, w, h, format, type, pixels);
    }

    @Override
    public void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format,
        int type, ByteBuffer pixels) {
        subImage(res().boundTexture(), level, xoffset, yoffset, width, height, format, type, address(pixels));
    }

    @Override
    public void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format,
        int type, IntBuffer pixels) {
        subImage(res().boundTexture(), level, xoffset, yoffset, width, height, format, type, address(pixels));
    }

    @Override
    public void textureSubImage2D(int texture, int level, int xoffset, int yoffset, int width, int height, int format,
        int type, ByteBuffer pixels) {
        subImage(res().texture(texture), level, xoffset, yoffset, width, height, format, type, address(pixels));
    }

    @Override
    public void textureSubImage2D(int texture, int level, int xoffset, int yoffset, int width, int height, int format,
        int type, IntBuffer pixels) {
        subImage(res().texture(texture), level, xoffset, yoffset, width, height, format, type, address(pixels));
    }

    @Override
    public void generateMipmap(int target) {
        GLResources.GLTexture t = res().boundTexture();
        if (t != null) res().generateMipmaps(t);
    }

    @Override
    public void generateTextureMipmap(int texture) {
        GLResources.GLTexture t = res().texture(texture);
        if (t != null) res().generateMipmaps(t);
    }

    private static void parameter(GLResources.GLTexture t, int pname, float value) {
        if (t == null) return;
        int v = (int) value;
        switch (pname) {
            case 0x2801:
                t.minFilter = v;
                break;
            case 0x2800:
                t.magFilter = v;
                break;
            case 0x2802:
                t.wrapS = v;
                break;
            case 0x2803:
                t.wrapT = v;
                break;
            case 0x813D:
                t.maxLevel = v;
                break;
            case 0x813A:
                t.minLod = value;
                break;
            case 0x813B:
                t.maxLod = value;
                break;
            case 0x8501:
                t.lodBias = value;
                break;
            case 0x84FE:
                t.anisotropy = value;
                break;
            case 0x884C:
                t.compareMode = v;
                break;
            case 0x884D:
                t.compareFunc = v;
                break;
            default:
                return;
        }
        t.samplerVersion++;
    }

    private static float parameterValue(GLResources.GLTexture t, int pname) {
        if (t == null) return 0;
        switch (pname) {
            case 0x2801:
                return t.minFilter;
            case 0x2800:
                return t.magFilter;
            case 0x2802:
                return t.wrapS;
            case 0x2803:
                return t.wrapT;
            case 0x813D:
                return t.maxLevel;
            case 0x813A:
                return t.minLod;
            case 0x813B:
                return t.maxLod;
            case 0x8501:
                return t.lodBias;
            case 0x84FE:
                return t.anisotropy;
            case 0x884C:
                return t.compareMode;
            case 0x884D:
                return t.compareFunc;
            default:
                return 0;
        }
    }

    @Override
    public void texParameteri(int target, int pname, int param) {
        parameter(res().boundTexture(), pname, param);
    }

    @Override
    public void texParameterf(int target, int pname, float param) {
        parameter(res().boundTexture(), pname, param);
    }

    @Override
    public void texParameteriv(int target, int pname, IntBuffer params) {
        parameter(res().boundTexture(), pname, params.get(params.position()));
    }

    @Override
    public void texParameterfv(int target, int pname, FloatBuffer params) {
        parameter(res().boundTexture(), pname, params.get(params.position()));
    }

    @Override
    public void textureParameteri(int texture, int target, int pname, int param) {
        parameter(res().texture(texture), pname, param);
    }

    @Override
    public void textureParameterf(int texture, int target, int pname, float param) {
        parameter(res().texture(texture), pname, param);
    }

    @Override
    public void textureParameteriv(int texture, int target, int pname, IntBuffer params) {
        parameter(res().texture(texture), pname, params.get(params.position()));
    }

    @Override
    public int getTexParameteri(int target, int pname) {
        return (int) parameterValue(res().boundTexture(), pname);
    }

    @Override
    public float getTexParameterf(int target, int pname) {
        return parameterValue(res().boundTexture(), pname);
    }

    @Override
    public int getTextureParameteri(int texture, int target, int pname) {
        return (int) parameterValue(res().texture(texture), pname);
    }

    @Override
    public float getTextureParameterf(int texture, int target, int pname) {
        return parameterValue(res().texture(texture), pname);
    }

    private int levelParameter(GLResources.GLTexture t, int target, int level, int pname) {
        if (target == GL_PROXY_TEXTURE_2D) {
            return pname == 0x1000 ? proxyWidth : pname == 0x1001 ? proxyHeight : 0;
        }
        if (t == null || t.image == null) return 0;
        switch (pname) {
            case 0x1000:
                return Math.max(1, t.image.width >> level);
            case 0x1001:
                return Math.max(1, t.image.height >> level);
            case 0x1003:
                return t.internal;
            default:
                return 0;
        }
    }

    @Override
    public int getTexLevelParameteri(int target, int level, int pname) {
        return levelParameter(res().boundTexture(), target, level, pname);
    }

    @Override
    public int getTextureLevelParameteri(int texture, int level, int pname) {
        return levelParameter(res().texture(texture), GL_TEXTURE_2D, level, pname);
    }

    // ------------------------------------------------------------------ sync objects

    /** A sync object is the number of the frame it was made in; it is signalled once that frame is done on the GPU. */
    @Override
    public long fenceSync(int condition, int flags) {
        return res().dev.currentFrame();
    }

    @Override
    public int clientWaitSync(long sync, int flags, long timeout) {
        VulkanDevice d = res().dev;
        if (d.isDone(sync)) return 0x911A; // ALREADY_SIGNALED
        if (timeout == 0) return 0x911B; // TIMEOUT_EXPIRED
        // A frame still being recorded cannot finish before it is submitted; OpenGL would flush here, and the
        // streaming code that waits only ever waits for older frames.
        if (sync >= d.currentFrame()) return 0x911C;
        d.waitFor(sync);
        return 0x911C; // CONDITION_SATISFIED
    }

    @Override
    public int getSynci(long sync, int pname, IntBuffer length) {
        if (length != null) length.put(length.position(), 1);
        return res().dev.isDone(sync) ? 0x9119 : 0x9118; // SIGNALED / UNSIGNALED
    }

    @Override
    public void deleteSync(long sync) {}

    @Override
    public void waitSync(long sync, int flags, long timeout) {}

    // ------------------------------------------------------------------ not drawn yet (generated)

    @Override
    public void flush() {}

    @Override
    public void finish() {}

    @Override
    public void enable(int cap) {}

    @Override
    public void enablei(int cap, int index) {}

    @Override
    public void disable(int cap) {}

    @Override
    public void disablei(int cap, int index) {}

    @Override
    public void blendFuncSeparatei(int buf, int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {}

    @Override
    public void blendFunc(int sfactor, int dfactor) {}

    @Override
    public void blendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {}

    @Override
    public void blendEquation(int mode) {}

    @Override
    public void blendEquationSeparate(int modeRGB, int modeAlpha) {}

    @Override
    public void blendColor(float red, float green, float blue, float alpha) {}

    @Override
    public void depthFunc(int func) {}

    @Override
    public void depthMask(boolean flag) {}

    @Override
    public void colorMask(boolean red, boolean green, boolean blue, boolean alpha) {}

    @Override
    public void cullFace(int mode) {}

    @Override
    public void frontFace(int mode) {}

    @Override
    public void polygonMode(int face, int mode) {}

    @Override
    public void polygonOffset(float factor, float units) {}

    @Override
    public void stencilFunc(int func, int ref, int mask) {}

    @Override
    public void stencilOp(int sfail, int dpfail, int dppass) {}

    @Override
    public void stencilMask(int mask) {}

    @Override
    public void stencilFuncSeparate(int face, int func, int ref, int mask) {}

    @Override
    public void stencilOpSeparate(int face, int sfail, int dpfail, int dppass) {}

    @Override
    public void stencilMaskSeparate(int face, int mask) {}

    @Override
    public void viewport(int x, int y, int width, int height) {}

    @Override
    public void depthRange(double nearVal, double farVal) {}

    @Override
    public void scissor(int x, int y, int width, int height) {}

    @Override
    public void lineWidth(float width) {}

    @Override
    public void pointSize(float size) {}

    @Override
    public void logicOp(int opcode) {}

    @Override
    public void hint(int target, int mode) {}

    @Override
    public void drawArrays(int mode, int first, int count) {}

    @Override
    public void drawElements(int mode, int count, int type, long indices) {}

    @Override
    public void multiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride) {}

    @Override
    public void drawElementsInstanced(int mode, int count, int type, long indices, int primcount) {}

    @Override
    public void drawArraysInstanced(int mode, int first, int count, int primcount) {}

    @Override
    public void drawElementsBaseVertex(int mode, int count, int type, long indices, int baseVertex) {}

    @Override
    public void multiDrawElementsBaseVertex(int mode, long pCount, int type, long pIndices, int drawcount,
        long pBaseVertex) {}

    @Override
    public void multiDrawArrays(int mode, IntBuffer firsts, IntBuffer counts) {}

    @Override
    public void primitiveRestartIndex(int index) {}

    @Override
    public void pointParameterf(int pname, float param) {}

    @Override
    public void pointParameteri(int pname, int param) {}

    @Override
    public void drawBuffer(int mode) {}

    @Override
    public void dispatchCompute(int numGroupsX, int numGroupsY, int numGroupsZ) {}

    @Override
    public void dispatchComputeIndirect(long offset) {}

    @Override
    public void copyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width,
        int height) {}

    @Override
    public int genSamplers() {
        return nextId();
    }

    @Override
    public void deleteSamplers(int sampler) {}

    @Override
    public void bindSampler(int unit, int sampler) {}

    @Override
    public void samplerParameteri(int sampler, int pname, int param) {}

    @Override
    public void samplerParameterf(int sampler, int pname, float param) {}

    @Override
    public int genFramebuffers() {
        return nextId();
    }

    @Override
    public void deleteFramebuffers(int framebuffer) {}

    @Override
    public void framebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {}

    @Override
    public void framebufferTexture1D(int target, int attachment, int textarget, int texture, int level) {}

    @Override
    public void framebufferTexture3D(int target, int attachment, int textarget, int texture, int level, int zoffset) {}

    @Override
    public void framebufferTextureLayer(int target, int attachment, int texture, int level, int layer) {}

    @Override
    public void framebufferTexture(int target, int attachment, int texture, int level) {}

    @Override
    public int genRenderbuffers() {
        return nextId();
    }

    @Override
    public void deleteRenderbuffers(int renderbuffer) {}

    @Override
    public void bindRenderbuffer(int target, int renderbuffer) {}

    @Override
    public void renderbufferStorage(int target, int internalformat, int width, int height) {}

    @Override
    public void renderbufferStorageMultisample(int target, int samples, int internalformat, int width, int height) {}

    @Override
    public void framebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {}

    @Override
    public void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1,
        int mask, int filter) {}

    @Override
    public void drawBuffers(int buffer) {}

    @Override
    public void drawBuffers(IntBuffer bufs) {}

    @Override
    public void readBuffer(int mode) {}

    @Override
    public void readPixels(int x, int y, int width, int height, int format, int type, ByteBuffer pixels) {}

    @Override
    public void readPixels(int x, int y, int width, int height, int format, int type, FloatBuffer pixels) {}

    @Override
    public void readPixels(int x, int y, int width, int height, int format, int type, IntBuffer pixels) {}

    @Override
    public void readPixels(int x, int y, int width, int height, int format, int type, long pixelBufferOffset) {}

    @Override
    public void getTexImage(int target, int level, int format, int type, ByteBuffer pixels) {}

    @Override
    public void getTexImage(int target, int level, int format, int type, IntBuffer pixels) {}

    @Override
    public void getTexImage(int target, int level, int format, int type, long pixelBufferOffset) {}

    @Override
    public int getFramebufferAttachmentParameteri(int target, int attachment, int pname) {
        return 0;
    }

    @Override
    public int createShader(int type) {
        return nextId();
    }

    @Override
    public void deleteShader(int shader) {}

    @Override
    public void shaderSource(int shader, CharSequence source) {}

    @Override
    public void compileShader(int shader) {}

    @Override
    public int createProgram() {
        return nextId();
    }

    @Override
    public void deleteProgram(int program) {}

    @Override
    public void attachShader(int program, int shader) {}

    @Override
    public void detachShader(int program, int shader) {}

    @Override
    public void linkProgram(int program) {}

    @Override
    public void useProgram(int program) {}

    @Override
    public void getProgramiv(int program, int pname, IntBuffer params) {}

    @Override
    public String getActiveUniform(int program, int index, int maxLength, IntBuffer sizeType) {
        return null;
    }

    @Override
    public void getActiveUniform(int program, int index, IntBuffer length, IntBuffer size, IntBuffer type,
        ByteBuffer name) {}

    @Override
    public void getActiveAttrib(int program, int index, IntBuffer length, IntBuffer size, IntBuffer type,
        ByteBuffer name) {}

    @Override
    public String getActiveAttrib(int program, int index, int maxLength, IntBuffer sizeType) {
        return null;
    }

    @Override
    public void bindAttribLocation(int program, int index, CharSequence name) {}

    @Override
    public void getShaderSource(int shader, IntBuffer length, ByteBuffer source) {}

    @Override
    public void uniform1i(int location, int v0) {}

    @Override
    public void uniform1f(int location, float v0) {}

    @Override
    public void uniform2f(int location, float v0, float v1) {}

    @Override
    public void uniform2i(int location, int v0, int v1) {}

    @Override
    public void uniform3f(int location, float v0, float v1, float v2) {}

    @Override
    public void uniform3i(int location, int v0, int v1, int v2) {}

    @Override
    public void uniform4f(int location, float v0, float v1, float v2, float v3) {}

    @Override
    public void uniform4i(int location, int v0, int v1, int v2, int v3) {}

    @Override
    public void uniform2(int location, FloatBuffer value) {}

    @Override
    public void uniform3(int location, FloatBuffer value) {}

    @Override
    public void uniform4(int location, FloatBuffer value) {}

    @Override
    public void uniform1iv(int location, IntBuffer value) {}

    @Override
    public void uniform2iv(int location, IntBuffer value) {}

    @Override
    public void uniform3iv(int location, IntBuffer value) {}

    @Override
    public void uniform4iv(int location, IntBuffer value) {}

    @Override
    public void uniformMatrix3(int location, boolean transpose, FloatBuffer value) {}

    @Override
    public void uniformMatrix4(int location, boolean transpose, FloatBuffer value) {}

    @Override
    public void vertexAttrib2f(int index, float v0, float v1) {}

    @Override
    public void vertexAttrib3f(int index, float v0, float v1, float v2) {}

    @Override
    public void vertexAttrib4f(int index, float v0, float v1, float v2, float v3) {}

    @Override
    public void bindBufferBase(int target, int index, int buffer) {}

    @Override
    public void bindBufferRange(int target, int index, int buffer, long offset, long size) {}

    @Override
    public void uniformBlockBinding(int program, int blockIndex, int binding) {}

    @Override
    public int getIndexedBufferBinding(int target, int index) {
        return 0;
    }

    @Override
    public boolean isFramebuffer(int framebuffer) {
        return true;
    }

    @Override
    public boolean isRenderbuffer(int renderbuffer) {
        return true;
    }

    @Override
    public boolean isSampler(int sampler) {
        return true;
    }

    @Override
    public boolean isQuery(int query) {
        return true;
    }

    @Override
    public int genVertexArrays() {
        return nextId();
    }

    @Override
    public void deleteVertexArrays(int array) {}

    @Override
    public void bindVertexArray(int array) {}

    @Override
    public boolean isVertexArray(int array) {
        return true;
    }

    @Override
    public void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {}

    @Override
    public void vertexAttribIPointer(int index, int size, int type, int stride, long pointer) {}

    @Override
    public void enableVertexAttribArray(int index) {}

    @Override
    public void disableVertexAttribArray(int index) {}

    @Override
    public void vertexAttribDivisor(int index, int divisor) {}

    @Override
    public void bindVertexBuffer(int bindingindex, int buffer, long offset, int stride) {}

    @Override
    public void vertexAttribFormat(int attribindex, int size, int type, boolean normalized, int relativeoffset) {}

    @Override
    public void vertexAttribIFormat(int attribindex, int size, int type, int relativeoffset) {}

    @Override
    public void vertexAttribBinding(int attribindex, int bindingindex) {}

    @Override
    public int createFramebuffers() {
        return nextId();
    }

    @Override
    public void namedFramebufferTexture(int framebuffer, int attachment, int texture, int level) {}

    @Override
    public void namedFramebufferReadBuffer(int framebuffer, int mode) {}

    @Override
    public void namedFramebufferDrawBuffers(int framebuffer, IntBuffer bufs) {}

    @Override
    public void blitNamedFramebuffer(int readFramebuffer, int drawFramebuffer, int srcX0, int srcY0, int srcX1,
        int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {}

    @Override
    public void copyTextureSubImage2D(int texture, int target, int level, int xoffset, int yoffset, int x, int y,
        int width, int height) {}

    @Override
    public void clearBufferSubData(int target, int internalFormat, long offset, long size, int format, int type,
        ByteBuffer data) {}

    @Override
    public void clearBufferData(int target, int internalformat, int format, int type, ByteBuffer data) {}

    @Override
    public void clearTexImage(int texture, int level, int format, int type) {}

    @Override
    public void bindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access,
        int format) {}

    @Override
    public void memoryBarrier(int barriers) {}

    @Override
    public void copyImageSubData(int srcName, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ, int dstName,
        int dstTarget, int dstLevel, int dstX, int dstY, int dstZ, int srcWidth, int srcHeight, int srcDepth) {}

}
