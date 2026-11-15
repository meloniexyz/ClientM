package com.clientm.module.impl;

import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.opengl.GL11;

import com.clientm.module.Category;
import com.clientm.module.Module;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Removes the distance fog on terrain, so the whole render distance is clearly visible. The sky keeps its own fog
 * (that is what blends the horizon), and water and lava still fog up when the camera is inside them.
 */
public class NoFogModule extends Module {

    private static NoFogModule instance;

    public NoFogModule() {
        super("nofog", "No Fog", Category.PERFORMANCE, true);
        instance = this;
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public String description() {
        return "No distance fog on terrain";
    }

    /** Used by the video presets. */
    public static void set(boolean noFog) {
        if (instance != null) instance.setEnabled(noFog);
    }

    @SubscribeEvent
    public void onFog(EntityViewRenderEvent.RenderFogEvent event) {
        // fogMode -1 is the sky pass; 0 is terrain.
        if (!enabled || event.fogMode < 0) return;
        // Push the fog far past anything that can be drawn.
        GL11.glFogf(GL11.GL_FOG_START, 100000f);
        GL11.glFogf(GL11.GL_FOG_END, 100001f);
    }
}
