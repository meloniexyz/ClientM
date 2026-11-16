package com.clientm.module.impl;

import com.clientm.ClientM;
import com.clientm.module.Category;
import com.clientm.module.Module;
import com.clientm.setting.BoolSetting;

/**
 * Cuts fixed per-frame costs between the game and the graphics driver.
 *
 * Fewer GPU sync points: Angelica asks the driver for a sync marker on each of its upload buffers every frame. At
 * four-digit frame rates those requests are a measurable share of the frame, so the mixin in
 * {@code MixinGlStreamingRing_BatchSync} only places a marker once enough data has been written.
 *
 * High GPU priority: asks Windows to schedule this process's GPU work ahead of other programs (browser, Discord,
 * recorders). It does nothing when the game is the only program using the GPU.
 */
public class FrameSubmitModule extends Module {

    private static final int NORMAL = 2;
    private static final int HIGH = 4;

    private static FrameSubmitModule instance;
    private static int appliedPriority = NORMAL;
    private static boolean priorityBroken;
    private static boolean syncLogged;

    private final BoolSetting batchSync = add(new BoolSetting("batchSync", "Fewer GPU sync points", true));
    private final BoolSetting gpuPriority = add(new BoolSetting("gpuPriority", "High GPU priority (Windows)", true));

    public FrameSubmitModule() {
        super("framesubmit", "Fast Frame Submit", Category.PERFORMANCE, true);
        instance = this;
    }

    @Override
    public String description() {
        return "Less driver overhead per frame";
    }

    /** Read by the streaming-buffer mixin once per frame per buffer. */
    public static boolean batchSync() {
        FrameSubmitModule m = instance;
        if (m == null || !m.enabled || !m.batchSync.value) return false;
        if (!syncLogged) {
            syncLogged = true;
            ClientM.LOG.info("Streaming buffer sync batching is active");
        }
        return true;
    }

    @Override
    public void onTick() {
        setPriority(gpuPriority.value ? HIGH : NORMAL);
    }

    @Override
    public void onDisable() {
        setPriority(NORMAL);
    }

    private static void setPriority(int wanted) {
        if (wanted == appliedPriority || priorityBroken) return;
        appliedPriority = wanted;
        if (!System.getProperty("os.name", "")
            .startsWith("Windows")) {
            priorityBroken = true;
            return;
        }
        try {
            // JNA ships with the game but is not on the compile classpath, so it is reached by name.
            Class<?> library = Class.forName("com.sun.jna.NativeLibrary");
            Class<?> function = Class.forName("com.sun.jna.Function");
            Class<?> pointer = Class.forName("com.sun.jna.Pointer");
            java.lang.reflect.Method open = library.getMethod("getInstance", String.class);
            java.lang.reflect.Method find = library.getMethod("getFunction", String.class);
            Object current = find.invoke(open.invoke(null, "kernel32"), "GetCurrentProcess");
            Object process = function.getMethod("invoke", Class.class, Object[].class)
                .invoke(current, pointer, new Object[0]);
            Object set = find.invoke(open.invoke(null, "gdi32"), "D3DKMTSetProcessSchedulingPriorityClass");
            int status = (Integer) function.getMethod("invokeInt", Object[].class)
                .invoke(set, (Object) new Object[] { process, wanted });
            ClientM.LOG.info("GPU priority {} requested, Windows answered 0x{}", wanted, Integer.toHexString(status));
        } catch (Throwable t) {
            priorityBroken = true;
            ClientM.LOG.info("GPU priority is not available here ({})", t.toString());
        }
    }
}
