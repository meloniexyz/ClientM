package com.clientm.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/** Small edits to compiled SPIR-V. */
@Lwjgl3Aware
final class SpirvPatch {

    private static final int OP_DECORATE = 71, DECORATION_BINDING = 33, DECORATION_DESCRIPTOR_SET = 34;

    private SpirvPatch() {}

    /**
     * Moves every resource to descriptor set 0: a resource in set {@code s} at binding {@code b} ends up at binding
     * {@code base[s] + b}.
     */
    static void mergeSets(ByteBuffer code, int[] base) {
        IntBuffer w = code.order(ByteOrder.nativeOrder())
            .asIntBuffer();
        Map<Integer, Integer> setOf = new HashMap<>(), setWord = new HashMap<>(), bindingWord = new HashMap<>();
        int i = 5; // after the header
        while (i < w.limit()) {
            int first = w.get(i);
            int count = first >>> 16, op = first & 0xFFFF;
            if (count == 0) break;
            if (op == OP_DECORATE && count >= 4) {
                int target = w.get(i + 1), decoration = w.get(i + 2);
                if (decoration == DECORATION_DESCRIPTOR_SET) {
                    setOf.put(target, w.get(i + 3));
                    setWord.put(target, i + 3);
                } else if (decoration == DECORATION_BINDING) {
                    bindingWord.put(target, i + 3);
                }
            }
            i += count;
        }
        for (Map.Entry<Integer, Integer> e : setOf.entrySet()) {
            int set = e.getValue();
            Integer bw = bindingWord.get(e.getKey());
            if (bw == null || set < 0 || set >= base.length) continue;
            w.put(bw, base[set] + w.get(bw));
            w.put(setWord.get(e.getKey()), 0);
        }
    }
}
