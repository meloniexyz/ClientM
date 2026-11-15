package com.clientm.module.impl;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import com.clientm.ClientM;
import com.clientm.module.Category;
import com.clientm.module.Module;
import com.clientm.setting.IntSetting;

/**
 * Custom colour for the flash on a mob or player that was just hit. Angelica already draws that flash in the same
 * pass as the model (no second draw), with a fixed red; this only swaps the colour it mixes in. Without Angelica the
 * module does nothing.
 */
public class HitColourModule extends Module {

    private static final MethodHandle GET_ALPHA;
    private static final MethodHandle SET_COLOUR;
    private static HitColourModule instance;
    private static boolean broken;
    private static boolean hookLogged;

    static {
        MethodHandle get = null;
        MethodHandle set = null;
        try {
            Class<?> c = Class.forName("com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            get = lookup.findStatic(c, "getOverlayA", MethodType.methodType(float.class));
            set = lookup.findStatic(
                c,
                "setOverlayColor",
                MethodType.methodType(void.class, float.class, float.class, float.class, float.class));
        } catch (Throwable t) {
            ClientM.LOG.info("Hit colour: Angelica's overlay tint is not available ({})", t.toString());
        }
        GET_ALPHA = get;
        SET_COLOUR = set;
    }

    private final IntSetting red = add(new IntSetting("red", "Red", 255, 0, 255));
    private final IntSetting green = add(new IntSetting("green", "Green", 0, 0, 255));
    private final IntSetting blue = add(new IntSetting("blue", "Blue", 0, 0, 255));
    private final IntSetting strength = add(new IntSetting("strength", "Strength %", 30, 0, 100));

    public HitColourModule() {
        super("hitcolour", "Hit Colour", Category.VISUAL, false);
        instance = this;
    }

    @Override
    public String description() {
        return SET_COLOUR == null ? "Needs Angelica" : "Colour of the flash on hit mobs and players";
    }

    /** Called by the mixin right before a model is drawn; replaces Angelica's red if a hit flash is active. */
    public static void apply() {
        HitColourModule m = instance;
        if (m == null || !m.enabled || SET_COLOUR == null || broken) return;
        try {
            if ((float) GET_ALPHA.invokeExact() <= 0f) return;
            SET_COLOUR
                .invokeExact(m.red.value / 255f, m.green.value / 255f, m.blue.value / 255f, m.strength.value / 100f);
            if (!hookLogged) {
                hookLogged = true;
                ClientM.LOG.info("Hit colour hook is active");
            }
        } catch (Throwable t) {
            broken = true;
            ClientM.LOG.warn("Hit colour switched off after an error", t);
        }
    }
}
