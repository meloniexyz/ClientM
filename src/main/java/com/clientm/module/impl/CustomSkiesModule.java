package com.clientm.module.impl;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.clientm.ClientM;
import com.clientm.module.Category;
import com.clientm.module.Module;

/**
 * Skies that resource packs bring (their mcpatcher/sky folder). They need Angelica's "better skies" support, which
 * costs FPS every frame once it is loaded, even when no pack sky is drawn, and which can only be loaded or left out
 * when the game starts. So this switch does two things:
 *
 * - it stops or allows drawing pack skies right away (the hook is in MixinSkyRenderer_Toggle);
 * - it keeps "better skies" in Angelica's config/mcpatcherforge.cfg in step, so with the switch off the sky support
 * is not loaded at all from the next start.
 */
public class CustomSkiesModule extends Module {

    private static final Pattern SECTION = Pattern.compile("better_skies\\s*\\{[^}]*\\}");

    private static CustomSkiesModule instance;
    /** What the config file was last set to by this session; null until checked. */
    private Boolean written;

    public CustomSkiesModule() {
        super("customskies", "Custom Skies", Category.VISUAL, true);
        instance = this;
    }

    @Override
    public String description() {
        return "Skies from resource packs; turning on or off fully applies after a restart";
    }

    public static boolean active() {
        CustomSkiesModule m = instance;
        return m == null || m.enabled;
    }

    /** Called every game tick; touches the file only when the switch and the file disagree. */
    public static void sync() {
        CustomSkiesModule m = instance;
        if (m == null || m.written != null && m.written == m.enabled) return;
        m.written = m.enabled;
        m.writeConfig(m.enabled);
    }

    private void writeConfig(boolean on) {
        try {
            File file = new File(mc.mcDataDir, "config/mcpatcherforge.cfg");
            String text = file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : null;
            String changed;
            Matcher section = text == null ? null : SECTION.matcher(text);
            if (section != null && section.find()) {
                String body = section.group();
                String newBody = body.replaceFirst("B:enabled=(true|false)", "B:enabled=" + on);
                if (!newBody.contains("B:enabled=")) newBody = newBody.replaceFirst("\\{", "{\n    B:enabled=" + on);
                if (on && newBody.contains("B:skybox=false"))
                    newBody = newBody.replace("B:skybox=false", "B:skybox=true");
                changed = text.substring(0, section.start()) + newBody + text.substring(section.end());
            } else {
                String block = "better_skies {\n    B:enabled=" + on + "\n    B:skybox=true\n}\n";
                changed = text == null ? "# Configuration file\n\n" + block : text + "\n" + block;
            }
            if (changed.equals(text)) return;
            file.getParentFile()
                .mkdirs();
            Files.write(file.toPath(), changed.getBytes(StandardCharsets.UTF_8));
            ClientM.LOG.info("Custom Skies: sky support will be {} from the next start", on ? "loaded" : "left out");
        } catch (Throwable t) {
            ClientM.LOG.info("Custom Skies could not update mcpatcherforge.cfg ({})", t.toString());
        }
    }
}
