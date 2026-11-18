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
 * It is only used when asked for: the environment variable CLIENTM_RENDERER=vulkan, or -Dclientm.renderer=vulkan, or
 * a file config/clientm-renderer.txt containing "vulkan". Anything going wrong falls back to OpenGL.
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
        return value != null && value.toLowerCase(Locale.ROOT)
            .startsWith("vulkan");
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
