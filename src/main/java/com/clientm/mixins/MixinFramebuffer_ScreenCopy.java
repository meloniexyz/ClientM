package com.clientm.mixins;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * On the ClientM Vulkan renderer, Minecraft's copy of its framebuffer to the screen at the end of each frame is not
 * drawn: the window is filled from the framebuffer directly (com.clientm.vulkan.VulkanRenderer.screenFromTexture).
 * The method still runs, so every bit of OpenGL state it sets is the same; only its one draw is left out.
 */
@Mixin(Framebuffer.class)
public abstract class MixinFramebuffer_ScreenCopy {

    @Shadow
    public int framebufferTexture;
    @Shadow
    public int framebufferWidth, framebufferHeight, framebufferTextureWidth, framebufferTextureHeight;

    private static MethodHandle clientm$take, clientm$done;
    private static boolean clientm$looked;
    private boolean clientm$taken;

    private static void clientm$lookUp() {
        if (clientm$looked) return;
        clientm$looked = true;
        try {
            Class<?> gate = Class.forName("com.clientm.vulkan.VulkanGate");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            clientm$take = lookup
                .findStatic(gate, "screenFromTexture", MethodType.methodType(boolean.class, int.class));
            clientm$done = lookup.findStatic(gate, "screenCopyDone", MethodType.methodType(void.class));
        } catch (Throwable t) {
            // Not an LWJGL 3 launch: the copy is drawn as usual.
        }
    }

    // func_147615_c: framebufferRender(width, height)
    @Inject(method = "func_147615_c", at = @At("HEAD"))
    private void clientm$screenFromFramebuffer(int width, int height, CallbackInfo ci) {
        clientm$taken = false;
        Minecraft mc = Minecraft.getMinecraft();
        if ((Object) this != mc.getFramebuffer() || width != framebufferWidth
            || height != framebufferHeight
            || framebufferWidth != framebufferTextureWidth
            || framebufferHeight != framebufferTextureHeight) return;
        clientm$lookUp();
        if (clientm$take == null) return;
        try {
            clientm$taken = (boolean) clientm$take.invokeExact(framebufferTexture);
        } catch (Throwable t) {
            clientm$take = null;
        }
    }

    @Inject(method = "func_147615_c", at = @At("RETURN"))
    private void clientm$screenCopyDone(int width, int height, CallbackInfo ci) {
        if (!clientm$taken || clientm$done == null) return;
        clientm$taken = false;
        try {
            clientm$done.invokeExact();
        } catch (Throwable ignored) {}
    }
}
