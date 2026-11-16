package com.clientm.module.impl;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.clientm.ClientM;
import com.clientm.module.Category;
import com.clientm.module.Module;

/**
 * Chooses what "fullscreen" means. On: a borderless window the size of the screen, which switches to other programs
 * instantly. Off: normal (exclusive) fullscreen. The switch itself belongs to lwjgl3ify; this module sets it, keeps
 * lwjgl3ify's own config file in step, and re-enters fullscreen so the change shows at once.
 */
public class BorderlessFullscreenModule extends Module {

    /** What lwjgl3ify was last told; null until the first tick. */
    private static BorderlessFullscreenModule instance;
    private Boolean applied;
    private boolean broken;

    public BorderlessFullscreenModule() {
        super("borderless", "Borderless Fullscreen", Category.VISUAL, false);
        instance = this;
    }

    @Override
    public String description() {
        return "Fullscreen as a borderless window; off for normal fullscreen";
    }

    /**
     * Called every game tick whether the module is on or off, so that "off" is also carried through at startup and
     * right after the switch is flipped.
     */
    public static void sync() {
        BorderlessFullscreenModule m = instance;
        if (m != null) m.apply(m.enabled);
    }

    private void apply(boolean borderless) {
        if (broken || applied != null && applied == borderless) return;
        applied = borderless;
        try {
            java.lang.reflect.Field field = Class.forName("me.eigenraven.lwjgl3ify.core.Config")
                .getField("WINDOW_BORDERLESS_REPLACES_FULLSCREEN");
            boolean before = field.getBoolean(null);
            if (before == borderless) return;
            field.setBoolean(null, borderless);
            save(borderless);
            ClientM.LOG.info("Fullscreen mode: {}", borderless ? "borderless window" : "normal");
            // Leave and re-enter fullscreen so the new mode is used straight away.
            if (mc.isFullScreen()) {
                mc.toggleFullscreen();
                mc.toggleFullscreen();
            }
        } catch (Throwable t) {
            broken = true;
            ClientM.LOG.info("Fullscreen mode cannot be changed here ({})", t.toString());
        }
    }

    /** Keeps lwjgl3ify's file in step, so the game also starts in the chosen mode. */
    private void save(boolean borderless) {
        try {
            File file = new File(mc.mcDataDir, "config/lwjgl3ify.cfg");
            if (!file.isFile()) return;
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String changed = text.replaceAll("B:borderless=(true|false)", "B:borderless=" + borderless);
            if (!changed.equals(text)) Files.write(file.toPath(), changed.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            ClientM.LOG.info("Could not update lwjgl3ify.cfg ({})", t.toString());
        }
    }
}
