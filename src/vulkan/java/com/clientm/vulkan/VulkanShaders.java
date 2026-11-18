package com.clientm.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import com.gtnewhorizons.angelica.glsm.shader.SpirvCompiler;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * The game's shaders and programs on Vulkan.
 *
 * Shader source goes through Angelica's own translation (the one its SDL GPU backend uses) and is compiled to SPIR-V.
 * The translation leaves each stage with one block of values and its textures as numbered slots:
 *
 * <pre>
 *   set 0: vertex-stage textures      set 1: the vertex stage's value block
 *   set 2: fragment-stage textures    set 3: the fragment stage's value block
 * </pre>
 *
 * A stage's value block is filled either from the program's own values (glUniform calls, kept here per program) or,
 * when the program has a uniform block bound to a buffer (Angelica's fixed-function emulation does), from that
 * buffer.
 */
@Lwjgl3Aware
final class VulkanShaders {

    private static final Logger LOG = LogManager.getLogger("ClientM/Vulkan");
    static final int GL_VERTEX_SHADER = 0x8B31, GL_FRAGMENT_SHADER = 0x8B30;

    static final class GLShader {

        final int id, type;
        String source = "";
        ByteBuffer spirv;
        ShaderManager.StageReflection reflection;
        boolean compiled;
        String log = "";

        GLShader(int id, int type) {
            this.id = id;
            this.type = type;
        }
    }

    /**
     * One uniform as the program sees it: where its value lives in each stage's block, or which unit a sampler uses.
     */
    static final class Uniform {

        final String name;
        int location, arrayLen = 1;
        int vsOffset = -1, fsOffset = -1, arrayStride, columns = 1, vectorSize = 1, baseType;
        boolean sampler;
        int unit;

        Uniform(String name) {
            this.name = name;
        }
    }

    static final class GLProgram {

        final int id;
        GLShader vs, fs;
        boolean linked;
        String log = "";
        final Map<String, Integer> attribBindings = new HashMap<>();
        final Map<String, Integer> attribLocations = new HashMap<>();
        ShaderManager.StageReflection vsReflection, fsReflection;
        long vsModule, fsModule, pipelineLayout;
        long[] setLayouts = new long[4];
        int vsUboSize, fsUboSize;
        /** Bindings in the single descriptor set. */
        int vsUboBinding, fsSamplerBase, fsUboBinding;
        ByteBuffer vsValues, fsValues;
        boolean valuesDirty = true;
        final Map<String, Uniform> uniforms = new HashMap<>();
        final List<Uniform> byLocation = new ArrayList<>();
        /** Sampler names per stage, in descriptor binding order. */
        List<String> vsSamplers = new ArrayList<>(), fsSamplers = new ArrayList<>();
        /** Uniform buffer binding point the program's block was attached to with glUniformBlockBinding, or -1. */
        int externalUboBinding = -1;
        /** Bumped when the program is relinked, so pipelines built for the old version are not reused. */
        int version;

        GLProgram(int id) {
            this.id = id;
        }
    }

    private final VulkanDevice dev;
    final Map<Integer, GLShader> shaders = new HashMap<>();
    final Map<Integer, GLProgram> programs = new HashMap<>();

    VulkanShaders(VulkanDevice dev) {
        this.dev = dev;
    }

    // ------------------------------------------------------------------ shaders

    void compile(GLShader s) {
        String raw = s.source;
        if (!raw.isEmpty() && raw.charAt(raw.length() - 1) == '\0') raw = raw.substring(0, raw.length() - 1);
        String translated;
        try {
            translated = ShaderManager.applyPrewarmTransformsFull(raw, s.type, null, null)
                .source();
        } catch (Throwable t) {
            translated = raw;
            LOG.warn("Shader {} could not be translated ({}); compiling it as written", s.id, t.toString());
        }
        int kind = s.type == GL_VERTEX_SHADER ? Shaderc.shaderc_vertex_shader : Shaderc.shaderc_fragment_shader;
        SpirvCompiler.Result r = SpirvCompiler
            .compile(translated, kind, "clientm-shader" + s.id, SpirvCompiler.Options.vulkanForced460Core());
        if (r.spirv() == null) {
            s.compiled = false;
            s.log = r.error() == null ? "compilation failed" : r.error();
            LOG.error("Shader {} did not compile for Vulkan: {}", s.id, s.log);
            return;
        }
        if (s.spirv != null) MemoryUtil.memFree(s.spirv);
        s.spirv = r.spirv();
        ShaderManager.remapSpirvForSDLGPU(s.spirv, s.type);
        s.reflection = ShaderManager.reflectStage(s.spirv, s.type == GL_VERTEX_SHADER);
        s.compiled = true;
        s.log = "";
    }

    // ------------------------------------------------------------------ programs

    void link(GLProgram p) {
        p.linked = false;
        if (p.vs == null || p.fs == null || !p.vs.compiled || !p.fs.compiled) {
            p.log = "a vertex and a fragment shader must be attached and compiled";
            return;
        }
        destroyProgramObjects(p);
        p.vsReflection = p.vs.reflection;
        p.fsReflection = p.fs.reflection;

        // Copies, so attribute and varying locations can be patched per program.
        ByteBuffer vsCode = copy(p.vs.spirv);
        ByteBuffer fsCode = copy(p.fs.spirv);
        resolveAttributes(p, vsCode);
        matchVaryings(p, fsCode);

        p.vsUboSize = p.vsReflection.uboSize();
        p.fsUboSize = p.fsReflection.uboSize();
        p.vsValues = p.vsUboSize > 0 ? MemoryUtil.memCalloc(p.vsUboSize) : null;
        p.fsValues = p.fsUboSize > 0 ? MemoryUtil.memCalloc(p.fsUboSize) : null;
        p.uniforms.clear();
        p.byLocation.clear();
        for (ShaderManager.UboMember m : p.vsReflection.uboMembers()) member(p, m, true);
        for (ShaderManager.UboMember m : p.fsReflection.uboMembers()) member(p, m, false);
        p.vsSamplers = new ArrayList<>(p.vsReflection.samplerNames());
        p.fsSamplers = new ArrayList<>(p.fsReflection.samplerNames());
        for (String name : p.vsSamplers) sampler(p, name);
        for (String name : p.fsSamplers) sampler(p, name);
        if (!p.vsReflection.storageImageNames()
            .isEmpty()
            || !p.fsReflection.storageImageNames()
                .isEmpty()) {
            LOG.warn("Program {} uses storage images, which the Vulkan renderer does not support yet", p.id);
        }

        // Vulkan allows one push-descriptor set per pipeline, so the four translated sets become ranges of set 0.
        p.vsUboBinding = p.vsSamplers.size();
        p.fsSamplerBase = p.vsUboBinding + 1;
        p.fsUboBinding = p.fsSamplerBase + p.fsSamplers.size();
        int[] base = { 0, p.vsUboBinding, p.fsSamplerBase, p.fsUboBinding };
        SpirvPatch.mergeSets(vsCode, base);
        SpirvPatch.mergeSets(fsCode, base);
        try (MemoryStack stack = stackPush()) {
            p.vsModule = module(stack, vsCode);
            p.fsModule = module(stack, fsCode);
            p.setLayouts[0] = singleSet(stack, p);
            VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pSetLayouts(stack.longs(p.setLayouts[0]));
            LongBuffer handle = stack.mallocLong(1);
            VulkanDevice.check(vkCreatePipelineLayout(dev.vk(), info, null, handle), "create a pipeline layout");
            p.pipelineLayout = handle.get(0);
        } finally {
            MemoryUtil.memFree(vsCode);
            MemoryUtil.memFree(fsCode);
        }
        p.version++;
        p.valuesDirty = true;
        p.linked = true;
        p.log = "";
        LOG.info(
            "Program {} linked for Vulkan: vertex values {} B, fragment values {} B, textures {}/{}, inputs {}",
            p.id,
            p.vsUboSize,
            p.fsUboSize,
            p.vsSamplers.size(),
            p.fsSamplers.size(),
            p.attribLocations);
    }

    private static ByteBuffer copy(ByteBuffer src) {
        ByteBuffer dst = MemoryUtil.memAlloc(src.remaining());
        MemoryUtil.memCopy(MemoryUtil.memAddress(src), MemoryUtil.memAddress(dst), src.remaining());
        return dst;
    }

    /** Attributes bound by name before linking get those locations; the rest keep their own. */
    private static void resolveAttributes(GLProgram p, ByteBuffer vsCode) {
        p.attribLocations.clear();
        IntBuffer words = vsCode.order(ByteOrder.nativeOrder())
            .asIntBuffer();
        for (ShaderManager.VsInput in : p.vsReflection.vsInputs()) {
            Integer wanted = p.attribBindings.get(in.name());
            int loc = in.originalLocation();
            if (wanted != null && wanted != loc
                && in.binaryOffset() > 0
                && words.get(in.binaryOffset()) == in.originalLocation()) {
                words.put(in.binaryOffset(), wanted);
                loc = wanted;
            }
            p.attribLocations.put(in.name(), loc);
        }
    }

    /** The fragment stage's inputs are moved to the locations the vertex stage writes them to (matched by name). */
    private static void matchVaryings(GLProgram p, ByteBuffer fsCode) {
        Map<String, Integer> out = new HashMap<>();
        for (ShaderManager.VsOutput o : p.vsReflection.vsOutputs()) out.put(o.name(), o.originalLocation());
        IntBuffer words = fsCode.order(ByteOrder.nativeOrder())
            .asIntBuffer();
        for (ShaderManager.FsInput in : p.fsReflection.fsInputs()) {
            Integer loc = out.get(in.name());
            if (loc == null || loc == in.originalLocation() || in.binaryOffset() <= 0) continue;
            if (words.get(in.binaryOffset()) == in.originalLocation()) words.put(in.binaryOffset(), loc);
        }
    }

    private static void member(GLProgram p, ShaderManager.UboMember m, boolean vertex) {
        Uniform u = p.uniforms.get(m.name());
        if (u == null) {
            u = new Uniform(m.name());
            u.arrayLen = Math.max(1, m.arrayLen());
            u.location = p.byLocation.size();
            for (int i = 0; i < u.arrayLen; i++) p.byLocation.add(u);
            p.uniforms.put(m.name(), u);
        }
        if (vertex) u.vsOffset = m.offset();
        else u.fsOffset = m.offset();
        u.arrayStride = m.arrayStride();
        u.columns = Math.max(1, m.columns());
        u.vectorSize = Math.max(1, m.vectorSize());
        u.baseType = m.baseType();
    }

    private static void sampler(GLProgram p, String name) {
        if (p.uniforms.containsKey(name)) return;
        Uniform u = new Uniform(name);
        u.sampler = true;
        u.location = p.byLocation.size();
        p.byLocation.add(u);
        p.uniforms.put(name, u);
    }

    private long module(MemoryStack stack, ByteBuffer code) {
        VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
            .sType$Default()
            .pCode(code);
        LongBuffer handle = stack.mallocLong(1);
        VulkanDevice.check(vkCreateShaderModule(dev.vk(), info, null, handle), "create a shader module");
        return handle.get(0);
    }

    /** One push-descriptor set: vertex textures, vertex value block, fragment textures, fragment value block. */
    private long singleSet(MemoryStack stack, GLProgram p) {
        int count = p.fsUboBinding + 1;
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(count, stack);
        for (int i = 0; i < count; i++) {
            boolean vertex = i <= p.vsUboBinding;
            boolean ubo = i == p.vsUboBinding || i == p.fsUboBinding;
            bindings.get(i)
                .binding(i)
                .descriptorType(ubo ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .stageFlags(vertex ? VK_SHADER_STAGE_VERTEX_BIT : VK_SHADER_STAGE_FRAGMENT_BIT);
        }
        return setLayout(stack, bindings);
    }

    private long setLayout(MemoryStack stack, VkDescriptorSetLayoutBinding.Buffer bindings) {
        VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
            .sType$Default()
            .flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
            .pBindings(bindings);
        LongBuffer handle = stack.mallocLong(1);
        VulkanDevice.check(vkCreateDescriptorSetLayout(dev.vk(), info, null, handle), "create a descriptor set layout");
        return handle.get(0);
    }

    void destroyProgramObjects(GLProgram p) {
        final long vs = p.vsModule, fs = p.fsModule, layout = p.pipelineLayout;
        final long[] sets = p.setLayouts.clone();
        final ByteBuffer vv = p.vsValues, fv = p.fsValues;
        p.vsModule = p.fsModule = p.pipelineLayout = 0;
        p.setLayouts = new long[4];
        p.vsValues = p.fsValues = null;
        if (vs == 0 && fs == 0 && layout == 0 && vv == null && fv == null) return;
        dev.later(() -> {
            if (vs != 0) vkDestroyShaderModule(dev.vk(), vs, null);
            if (fs != 0) vkDestroyShaderModule(dev.vk(), fs, null);
            if (layout != 0) vkDestroyPipelineLayout(dev.vk(), layout, null);
            for (long s : sets) if (s != 0) vkDestroyDescriptorSetLayout(dev.vk(), s, null);
            if (vv != null) MemoryUtil.memFree(vv);
            if (fv != null) MemoryUtil.memFree(fv);
        });
    }

    // ------------------------------------------------------------------ uniform values

    /** OpenGL uniform locations: "name", "name[0]" and "name[i]" for arrays. */
    static int location(GLProgram p, String name) {
        if (p == null || !p.linked) return -1;
        String base = name;
        int index = 0;
        int bracket = name.indexOf('[');
        if (bracket > 0 && name.endsWith("]")) {
            base = name.substring(0, bracket);
            try {
                index = Integer.parseInt(name.substring(bracket + 1, name.length() - 1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        Uniform u = p.uniforms.get(base);
        if (u == null || index < 0 || index >= u.arrayLen) return -1;
        return u.location + index;
    }

    /**
     * Writes a uniform's value into the stage blocks. Values are given as floats or ints in OpenGL order; vectors and
     * matrix columns are placed at std140 positions (16 bytes per matrix column).
     */
    static void write(GLProgram p, int location, float[] floats, int[] ints, int count, int components, int columns) {
        if (p == null || location < 0 || location >= p.byLocation.size()) return;
        Uniform u = p.byLocation.get(location);
        if (u.sampler) {
            if (ints != null) u.unit = ints[0];
            else if (floats != null) u.unit = (int) floats[0];
            return;
        }
        int element = location - u.location;
        for (int e = 0; e < count && element + e < u.arrayLen; e++) {
            int base = (element + e) * u.arrayStride;
            for (int c = 0; c < columns; c++) {
                for (int i = 0; i < components; i++) {
                    int src = (e * columns + c) * components + i;
                    int off = base + c * 16 + i * 4;
                    if (u.vsOffset >= 0 && p.vsValues != null && u.vsOffset + off + 4 <= p.vsUboSize) {
                        put(p.vsValues, u.vsOffset + off, floats, ints, src);
                    }
                    if (u.fsOffset >= 0 && p.fsValues != null && u.fsOffset + off + 4 <= p.fsUboSize) {
                        put(p.fsValues, u.fsOffset + off, floats, ints, src);
                    }
                }
            }
        }
        p.valuesDirty = true;
    }

    private static void put(ByteBuffer b, int off, float[] floats, int[] ints, int src) {
        if (floats != null) b.putFloat(off, floats[src]);
        else b.putInt(off, ints[src]);
    }
}
