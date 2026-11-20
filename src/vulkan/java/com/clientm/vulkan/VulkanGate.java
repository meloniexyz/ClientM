package com.clientm.vulkan;

import java.lang.reflect.Field;
import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.lwjglx.opengl.Display;
import org.lwjglx.opengl.PixelFormat;

import me.eigenraven.lwjgl3ify.api.DisplayEvents;
import me.eigenraven.lwjgl3ify.api.DisplayWindowContext;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * Decides whether this start uses the ClientM Vulkan renderer and, if so, creates the game window for Vulkan instead
 * of OpenGL. Called by name from MixinForgeHooksClient_VulkanDisplay in the main mod code.
 *
 * Vulkan is the default. OpenGL is chosen with a file config/clientm-renderer.txt containing "opengl" (the launcher's
 * renderer setting writes it), the environment variable CLIENTM_RENDERER=opengl or -Dclientm.renderer=opengl. Anything
 * going wrong while setting Vulkan up falls back to OpenGL.
 */
// Calls LWJGL 3 directly, so lwjgl3ify must not redirect it to its LWJGL 2 compatibility layer.
@Lwjgl3Aware
public final class VulkanGate {

    private static final Logger LOG = LogManager.getLogger("ClientM/Vulkan");

    private static volatile boolean engaged;
    private static volatile Throwable failure;
    private static boolean listening;
    private static VulkanDevice device;

    private VulkanGate() {}

    public static boolean requested() {
        String value = System.getProperty("clientm.renderer");
        if (value == null) value = System.getenv("CLIENTM_RENDERER");
        if (value == null) {
            try {
                java.io.File file = new java.io.File("config/clientm-renderer.txt");
                if (file.isFile()) value = new String(
                    java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            } catch (Throwable ignored) {}
        }
        boolean explicit = System.getProperty("clientm.renderer") != null || System.getenv("CLIENTM_RENDERER") != null;
        if (value != null && !value.isEmpty()) {
            String v = value.toLowerCase(Locale.ROOT);
            if (v.startsWith("opengl") || v.equals("gl")) return false;
        }
        if (!explicit && shaderPackSelected()) {
            LOG.info("A shader pack is selected: shader packs need OpenGL, so this start uses OpenGL");
            reason = "A shader pack is selected, and shader packs need OpenGL: this start uses OpenGL.";
            return false;
        }
        if (!explicit && crashedLastTime()) {
            LOG.warn("The Vulkan renderer did not shut down properly last time: this start uses OpenGL");
            reason = "ClientM's Vulkan renderer did not close properly last time, so this start uses OpenGL. "
                + "Pick Vulkan in the launcher to try it again.";
            return false;
        }
        return true;
    }

    /**
     * Called by the main mod (MixinFramebuffer_ScreenCopy) before Minecraft copies its framebuffer to the screen.
     * True when the Vulkan renderer will show that framebuffer directly, so the copy's draw is left out.
     */
    public static boolean screenFromTexture(int texture, int framebuffer) {
        if (!engaged) return false;
        Object backend = com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;
        return backend instanceof VulkanRenderBackend
            && ((VulkanRenderBackend) backend).screenFromTexture(texture, framebuffer);
    }

    /** Called by the main mod after Minecraft's framebuffer copy to the screen. */
    public static void screenCopyDone() {
        Object backend = com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;
        if (backend instanceof VulkanRenderBackend) ((VulkanRenderBackend) backend).screenCopyDone();
    }

    /** Why this start uses OpenGL although Vulkan is the default (shown in chat once in game), or null. */
    private static volatile String reason;

    public static String openGlReason() {
        return reason;
    }

    private static final java.io.File SHADERS = new java.io.File("config/shaders.properties");
    /** Exists while the Vulkan renderer runs; left behind when the game did not close properly. */
    private static final java.io.File RUNNING = new java.io.File("config/.clientm-vulkan-running");
    /** Written after such a start; Vulkan stays off until the player picks it again (the launcher removes it). */
    private static final java.io.File CRASHED = new java.io.File("config/clientm-vulkan-crashed.txt");

    /** Angelica's shader settings name an active shader pack (they use OpenGL features Vulkan does not cover). */
    private static long shadersStamp = Long.MIN_VALUE;
    private static boolean shadersSelected;

    public static boolean shaderPackSelected() {
        // The game asks every couple of seconds: the file is only read again when it changed.
        long stamp = SHADERS.lastModified();
        if (stamp == shadersStamp) return shadersSelected;
        shadersStamp = stamp;
        shadersSelected = readShaderPackSelected();
        return shadersSelected;
    }

    private static boolean readShaderPackSelected() {
        if (!SHADERS.isFile()) return false;
        java.util.Properties p = new java.util.Properties();
        try (java.io.Reader in = new java.io.InputStreamReader(
            new java.io.FileInputStream(SHADERS),
            java.nio.charset.StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (Throwable t) {
            return false;
        }
        String pack = p.getProperty("shaderPack", "")
            .trim();
        return !"false".equalsIgnoreCase(
            p.getProperty("enableShaders", "true")
                .trim())
            && !pack.isEmpty()
            && !pack.equals("(off)");
    }

    private static boolean crashedLastTime() {
        if (RUNNING.exists()) {
            RUNNING.delete();
            try {
                CRASHED.getParentFile()
                    .mkdirs();
                java.nio.file.Files.write(
                    CRASHED.toPath(),
                    ("The Vulkan renderer did not close properly last time, so ClientM uses OpenGL.\n"
                        + "Pick Vulkan in the ClientM launcher, or delete this file, to use Vulkan again.\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Throwable ignored) {}
        }
        return CRASHED.exists();
    }

    /** Called once Vulkan has the window, and again when it shuts down properly. */
    static void markRunning(boolean running) {
        try {
            if (running) {
                RUNNING.getParentFile()
                    .mkdirs();
                java.nio.file.Files.write(RUNNING.toPath(), new byte[0]);
            } else {
                RUNNING.delete();
            }
        } catch (Throwable ignored) {}
    }

    /** True once the window belongs to Vulkan; the backend reports itself available only then. */
    public static boolean isEngaged() {
        return engaged;
    }

    static VulkanDevice device() {
        return device;
    }

    /**
     * Creates the game window for Vulkan. Returns false (with OpenGL left to create the window as usual) when Vulkan
     * is not requested or could not be set up.
     */
    public static boolean createDisplay() {
        if (!requested()) return false;
        LOG.info("ClientM Vulkan renderer requested");
        try {
            Class.forName("org.lwjgl.vulkan.VK10");
        } catch (Throwable t) {
            LOG.warn("Vulkan is not available (LWJGL Vulkan bindings missing); using OpenGL");
            return false;
        }
        listen();
        failure = null;
        try {
            DisplayEvents.setCreateGLContext(false);
            Display.create(
                new PixelFormat().withDepthBits(24)
                    .withStencilBits(8),
                null,
                0L);
        } catch (Throwable t) {
            failure = t;
        }
        if (engaged && failure == null) {
            installDrawable();
            markRunning(true);
            LOG.info("Created the game window for Vulkan");
            return true;
        }
        LOG.error("Vulkan could not take the window; using OpenGL", failure);
        fallBack();
        return false;
    }

    private static void listen() {
        if (listening) return;
        listening = true;
        DisplayEvents.addPreWindowCreateListener((DisplayWindowContext ctx) -> {
            if (DisplayEvents.isCreateGLContextEnabled()) return;
            SDLProperties.SDL_SetBooleanProperty(ctx.props(), SDLVideo.SDL_PROP_WINDOW_CREATE_VULKAN_BOOLEAN, true);
        });
        DisplayEvents.addPostWindowCreateListener((DisplayWindowContext ctx) -> {
            if (ctx.glContextEnabled()) return;
            try {
                // Development aid: pretend this computer cannot run Vulkan, to test falling back to OpenGL.
                if (System.getenv("CLIENTM_VK_TEST_FAIL") != null) throw new IllegalStateException("test failure");
                VulkanDevice d = new VulkanDevice(ctx.window());
                d.create();
                device = d;
                engaged = true;
                // The game reads the OpenGL feature list while the window is created, so it is published now.
                com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND.onPostWindowCreate(ctx.window());
            } catch (Throwable t) {
                failure = t;
            }
        });
        DisplayEvents
            .addPreSwapchainInvalidatingChangeListener(change -> { if (device != null) device.windowChanged(); });
    }

    /** Frame swaps go to Display's drawable; this one hands them to the Vulkan backend. */
    private static void installDrawable() {
        try {
            Field f = Display.class.getDeclaredField("drawable");
            f.setAccessible(true);
            if (f.get(null) == null) f.set(null, new VulkanDrawable());
        } catch (Throwable t) {
            throw new IllegalStateException("Could not attach the Vulkan renderer to the window", t);
        }
    }

    private static void fallBack() {
        engaged = false;
        if (device != null) {
            device.destroy();
            device = null;
        }
        DisplayEvents.setCreateGLContext(true);
        try {
            if (Display.isCreated()) Display.destroy();
        } catch (Throwable ignored) {}
    }
}
