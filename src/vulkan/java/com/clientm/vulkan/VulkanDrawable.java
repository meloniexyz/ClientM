package com.clientm.vulkan;

import org.lwjglx.opengl.ContextGL;
import org.lwjglx.opengl.DrawableGL;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/** The window's drawable when it belongs to Vulkan: there is no OpenGL context, and swaps go to the Vulkan backend. */
// Calls LWJGL 3 directly, so lwjgl3ify must not redirect it to its LWJGL 2 compatibility layer.
@Lwjgl3Aware
final class VulkanDrawable extends DrawableGL {

    VulkanDrawable() {
        super((ContextGL) null);
    }

    @Override
    public void makeCurrent() {}

    @Override
    public void releaseContext() {}

    @Override
    public boolean isCurrent() {
        return VulkanGate.isEngaged();
    }

    @Override
    public ContextGL createSharedContext() {
        return null;
    }

    @Override
    public void destroy() {}

    @Override
    public void checkGLError() {}

    @Override
    public void setSwapInterval(int interval) {}

    @Override
    public void swapBuffers() {
        BackendManager.RENDER_BACKEND.handleSwapBuffers();
    }

    @Override
    public void initContext(float r, float g, float b) {}
}
