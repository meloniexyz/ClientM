package com.clientm.module.impl;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ResourceLocation;

import com.clientm.module.Category;
import com.clientm.module.Module;
import com.clientm.setting.BoolSetting;
import com.clientm.setting.ChoiceSetting;
import com.clientm.setting.IntSetting;

/**
 * Shows a small X on the crosshair when the server confirms that a player or mob you just attacked was hurt (the
 * server tells everyone nearby when an entity takes damage). It shows the moment that message arrives (Hit Fix
 * handles it on the next frame), so you know at once whether a hit registered. Nothing is sent to the server.
 */
public class HitMarkerModule extends Module {

    private static HitMarkerModule instance;

    private final ChoiceSetting colour = add(
        new ChoiceSetting("colour", "Colour", 0, "White", "Red", "Yellow", "Cyan"));
    private final IntSetting duration = add(new IntSetting("duration", "Shown for (ms)", 250, 100, 600));
    private final BoolSetting sound = add(new BoolSetting("sound", "Play a sound", false));

    private static final int[] COLOURS = { 0xFFFFFF, 0xFF4D5E, 0xFFE15A, 0x5AE6FF };

    /** For the feature test tour: confirmed hits shown. */
    public static volatile int shown;

    private int target = -1;
    private long attackedAt, shownAt;

    public HitMarkerModule() {
        super("hitmarker", "Hit Marker", Category.VISUAL, true);
        instance = this;
    }

    @Override
    public String description() {
        return "An X on the crosshair when the server confirms your hit";
    }

    /** Called when you attack an entity (MixinPlayerControllerMP_HitMarker). */
    public static void attacked(int entityId) {
        HitMarkerModule m = instance;
        if (m == null || !m.enabled) return;
        m.target = entityId;
        m.attackedAt = System.currentTimeMillis();
    }

    /** Called when the server says an entity was hurt (MixinNetHandlerPlayClient_ServerInfo). */
    public static void hurt(int entityId) {
        HitMarkerModule m = instance;
        if (m == null || !m.enabled || entityId != m.target) return;
        long now = System.currentTimeMillis();
        if (now - m.attackedAt > 750) return; // too long after your swing to be your hit
        m.target = -1;
        m.shownAt = now;
        shown++;
        if (m.sound.value && m.mc.getSoundHandler() != null) m.mc.getSoundHandler()
            .playSound(
                net.minecraft.client.audio.PositionedSoundRecord
                    .func_147674_a(new ResourceLocation("random.successful_hit"), 1.0F));
    }

    /** Draws the marker around the crosshair (every frame, from the crosshair overlay event). */
    public static void draw(ScaledResolution res) {
        HitMarkerModule m = instance;
        if (m == null || !m.enabled || m.shownAt == 0) return;
        long age = System.currentTimeMillis() - m.shownAt;
        if (age > m.duration.value) return;
        float fade = 1f - (float) age / m.duration.value;
        int alpha = Math.max(16, (int) (255 * fade));
        int argb = alpha << 24 | COLOURS[Math.max(0, Math.min(COLOURS.length - 1, m.colour.value))];
        int cx = res.getScaledWidth() / 2, cy = res.getScaledHeight() / 2;
        // Four short diagonal arms, starting a little away from the centre so the crosshair stays visible.
        for (int i = 3; i <= 5; i++) {
            Gui.drawRect(cx + i, cy + i, cx + i + 1, cy + i + 1, argb);
            Gui.drawRect(cx - i, cy + i, cx - i + 1, cy + i + 1, argb);
            Gui.drawRect(cx + i, cy - i, cx + i + 1, cy - i + 1, argb);
            Gui.drawRect(cx - i, cy - i, cx - i + 1, cy - i + 1, argb);
        }
    }
}
