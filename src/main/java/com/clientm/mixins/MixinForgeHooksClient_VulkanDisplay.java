package com.clientm.mixins;

import net.minecraftforge.client.ForgeHooksClient;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.clientm.ClientM;

/**
 * Before Forge creates the game window for OpenGL, the ClientM Vulkan renderer gets the chance to create it for Vulkan
 * (only when it was asked for; see com.clientm.vulkan.VulkanGate). It is reached by class name, because its code is
 * built against LWJGL 3 and only exists at run time on the LWJGL 3 launch.
 */
@Mixin(value = ForgeHooksClient.class, remap = false)
public abstract class MixinForgeHooksClient_VulkanDisplay {

    @Shadow
    static int stencilBits;

    @Inject(method = "createDisplay", at = @At("HEAD"), cancellable = true, require = 0)
    private static void clientm$vulkanWindow(CallbackInfo ci) {
        try {
            Class.forName("com.clientm.vulkan.TestWindow")
                .getMethod("place")
                .invoke(null);
        } catch (Throwable ignored) {
            // Not an LWJGL 3 launch, or no test placement asked for.
        }
        try {
            Class<?> gate = Class.forName("com.clientm.vulkan.VulkanGate");
            if (!(Boolean) gate.getMethod("requested")
                .invoke(null)) return;
            stencilBits = 8;
            if ((Boolean) gate.getMethod("createDisplay")
                .invoke(null)) ci.cancel();
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Not an LWJGL 3 launch: nothing to do.
        } catch (Throwable t) {
            ClientM.LOG.warn("ClientM Vulkan renderer could not start; using OpenGL", t);
        }
    }
}
