package com.clientm.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.clientm.ClientM;

import cpw.mods.fml.common.Loader;

/**
 * Reads and writes Angelica's renderer options. Done by reflection so ClientM still loads without Angelica, and so an
 * Angelica update that renames something degrades to a logged warning instead of a crash.
 */
public final class AngelicaBridge {

    private static boolean resolved;
    private static Object options;
    private static Method writeChanges;

    private AngelicaBridge() {}

    public static boolean available() {
        resolve();
        return options != null;
    }

    private static void resolve() {
        if (resolved) return;
        if (!Loader.isModLoaded("angelica")) {
            resolved = true;
            return;
        }
        try {
            Class<?> proxy = Class.forName("com.gtnewhorizons.angelica.proxy.ClientProxy");
            Object o = proxy.getMethod("options")
                .invoke(null);
            if (o == null) return; // Angelica has not loaded its options yet; try again on the next call
            writeChanges = o.getClass()
                .getMethod("writeChanges");
            options = o;
            resolved = true;
        } catch (Throwable t) {
            resolved = true;
            ClientM.LOG.warn("Angelica is loaded but its options could not be reached", t);
        }
    }

    /**
     * Sets options.&lt;section&gt;.&lt;field&gt;. Enum fields take the constant's name as a String.
     *
     * @return true if the stored value actually changed
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static boolean set(String section, String field, Object value) {
        if (!available()) return false;
        try {
            Object holder = options.getClass()
                .getField(section)
                .get(options);
            Field f = holder.getClass()
                .getField(field);
            if (f.getType()
                .isEnum() && value instanceof String) {
                value = Enum.valueOf((Class<Enum>) f.getType(), (String) value);
            }
            if (value.equals(f.get(holder))) return false;
            f.set(holder, value);
            return true;
        } catch (Throwable t) {
            ClientM.LOG.warn("Could not set Angelica option {}.{}: {}", section, field, t.toString());
            return false;
        }
    }

    /** Sets a public static field on one of Angelica's config classes. */
    public static boolean setStatic(String className, String field, Object value) {
        if (!Loader.isModLoaded("angelica")) return false;
        try {
            Field f = Class.forName(className)
                .getField(field);
            if (value.equals(f.get(null))) return false;
            f.set(null, value);
            return true;
        } catch (Throwable t) {
            ClientM.LOG.warn("Could not set Angelica setting {}: {}", field, t.toString());
            return false;
        }
    }

    /** Switches Angelica's VSync off right now and stores that choice. */
    public static void vsyncOff() {
        if (!Loader.isModLoaded("angelica")) return;
        try {
            Class.forName("com.gtnewhorizons.angelica.proxy.ClientProxy")
                .getMethod("toggleVSync", boolean.class)
                .invoke(null, false);
        } catch (Throwable t) {
            ClientM.LOG.warn("Could not switch Angelica's VSync off: {}", t.toString());
        }
    }

    public static void save() {
        if (!available()) return;
        try {
            writeChanges.invoke(options);
        } catch (Throwable t) {
            ClientM.LOG.warn("Could not save Angelica options", t);
        }
    }
}
