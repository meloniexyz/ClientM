package com.clientm.vulkan;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/** Writes and reads Angelica's shader reflection results, so cached shaders skip reflecting their SPIR-V again. */
@Lwjgl3Aware
final class ReflectionCache {

    private ReflectionCache() {}

    static void write(DataOutputStream out, ShaderManager.StageReflection r) throws IOException {
        ShaderManager.ResourceCounts c = r.counts();
        out.writeInt(c.numSamplers());
        out.writeInt(c.numUBOs());
        out.writeInt(c.numStorageBuffers());
        out.writeInt(c.numStorageTextures());
        strings(out, r.samplerNames());
        strings(out, r.unusedSamplerNames());
        strings(out, r.extraUniformNames());
        strings(out, r.storageImageNames());
        out.writeInt(r.uboSize());
        members(out, r.uboMembers());
        out.writeInt(
            r.vsInputs()
                .size());
        for (ShaderManager.VsInput i : r.vsInputs()) {
            out.writeUTF(i.name());
            out.writeInt(i.binaryOffset());
            out.writeInt(i.originalLocation());
            out.writeInt(i.vecSize());
            out.writeInt(i.baseType());
        }
        out.writeInt(
            r.vsOutputs()
                .size());
        for (ShaderManager.VsOutput o : r.vsOutputs()) {
            out.writeUTF(o.name());
            out.writeInt(o.originalLocation());
        }
        out.writeInt(
            r.fsInputs()
                .size());
        for (ShaderManager.FsInput i : r.fsInputs()) {
            out.writeUTF(i.name());
            out.writeInt(i.binaryOffset());
            out.writeInt(i.originalLocation());
        }
        out.writeInt(r.maxOutputLocation());
        out.writeInt(r.numReadonlyStorageBuffers());
        out.writeInt(r.numReadwriteStorageBuffers());
        out.writeInt(r.numReadonlyStorageTextures());
        out.writeInt(r.numReadwriteStorageTextures());
        ShaderManager.BlockReflection[] blocks = r.blocks();
        out.writeInt(blocks == null ? 0 : blocks.length);
        if (blocks != null) for (ShaderManager.BlockReflection b : blocks) {
            out.writeInt(b.size());
            out.writeInt(b.binding());
            members(out, b.members());
            out.writeBoolean(b.readOnly());
        }
    }

    static ShaderManager.StageReflection read(DataInputStream in) throws IOException {
        ShaderManager.ResourceCounts c = new ShaderManager.ResourceCounts(
            in.readInt(),
            in.readInt(),
            in.readInt(),
            in.readInt());
        List<String> samplers = strings(in);
        List<String> unused = strings(in);
        List<String> extra = strings(in);
        List<String> storage = strings(in);
        int uboSize = in.readInt();
        List<ShaderManager.UboMember> members = members(in);
        int n = in.readInt();
        List<ShaderManager.VsInput> vsInputs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String name = in.readUTF();
            int offset = in.readInt(), location = in.readInt(), vec = in.readInt(), type = in.readInt();
            vsInputs.add(new ShaderManager.VsInput(name, offset, location, vec, type));
        }
        n = in.readInt();
        List<ShaderManager.VsOutput> vsOutputs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String name = in.readUTF();
            vsOutputs.add(new ShaderManager.VsOutput(name, in.readInt()));
        }
        n = in.readInt();
        List<ShaderManager.FsInput> fsInputs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String name = in.readUTF();
            int offset = in.readInt(), location = in.readInt();
            fsInputs.add(new ShaderManager.FsInput(name, offset, location));
        }
        int maxOut = in.readInt();
        int roBuffers = in.readInt();
        int rwBuffers = in.readInt();
        int roTextures = in.readInt();
        int rwTextures = in.readInt();
        n = in.readInt();
        ShaderManager.BlockReflection[] blocks = new ShaderManager.BlockReflection[n];
        for (int i = 0; i < n; i++) {
            int size = in.readInt(), binding = in.readInt();
            List<ShaderManager.UboMember> m = members(in);
            blocks[i] = new ShaderManager.BlockReflection(size, binding, m, in.readBoolean());
        }
        return new ShaderManager.StageReflection(
            c,
            samplers,
            unused,
            extra,
            storage,
            uboSize,
            members,
            vsInputs,
            vsOutputs,
            fsInputs,
            maxOut,
            roBuffers,
            rwBuffers,
            roTextures,
            rwTextures,
            blocks);
    }

    private static void strings(DataOutputStream out, List<String> list) throws IOException {
        out.writeInt(list.size());
        for (String s : list) out.writeUTF(s);
    }

    private static List<String> strings(DataInputStream in) throws IOException {
        int n = in.readInt();
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(in.readUTF());
        return list;
    }

    private static void members(DataOutputStream out, List<ShaderManager.UboMember> list) throws IOException {
        out.writeInt(list.size());
        for (ShaderManager.UboMember m : list) {
            out.writeUTF(m.name());
            out.writeInt(m.offset());
            out.writeInt(m.size());
            out.writeInt(m.arrayStride());
            out.writeInt(m.vectorSize());
            out.writeInt(m.columns());
            out.writeInt(m.baseType());
            out.writeInt(m.arrayLen());
        }
    }

    private static List<ShaderManager.UboMember> members(DataInputStream in) throws IOException {
        int n = in.readInt();
        List<ShaderManager.UboMember> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String name = in.readUTF();
            int offset = in.readInt(), size = in.readInt(), stride = in.readInt(), vec = in.readInt(),
                columns = in.readInt(), type = in.readInt(), len = in.readInt();
            list.add(new ShaderManager.UboMember(name, offset, size, stride, vec, columns, type, len));
        }
        return list;
    }
}
