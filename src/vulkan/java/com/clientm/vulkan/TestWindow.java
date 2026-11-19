package com.clientm.vulkan;

import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;

import me.eigenraven.lwjgl3ify.api.DisplayEvents;
import me.eigenraven.lwjgl3ify.api.DisplayWindowContext;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * Development aid: CLIENTM_WINDOW_DISPLAY=x,y creates the game window with its top-left corner at that desktop point,
 * so test runs can open on another monitor. Moving the window after it exists can hang inside SDL, so it is placed
 * when it is created instead.
 */
@Lwjgl3Aware
public final class TestWindow {

    private static boolean done;

    private TestWindow() {}

    public static void place() {
        String where = System.getenv("CLIENTM_WINDOW_DISPLAY");
        if (where == null || done) return;
        done = true;
        String[] xy = where.split(",");
        final long x = Long.parseLong(xy[0].trim()), y = Long.parseLong(xy[1].trim());
        DisplayEvents.addPreWindowCreateListener((DisplayWindowContext ctx) -> {
            SDLProperties.SDL_SetNumberProperty(ctx.props(), SDLVideo.SDL_PROP_WINDOW_CREATE_X_NUMBER, x);
            SDLProperties.SDL_SetNumberProperty(ctx.props(), SDLVideo.SDL_PROP_WINDOW_CREATE_Y_NUMBER, y);
        });
    }
}
