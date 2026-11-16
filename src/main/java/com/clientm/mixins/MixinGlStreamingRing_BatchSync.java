package com.clientm.mixins;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.clientm.module.impl.FrameSubmitModule;

/**
 * Angelica's upload ring places a GPU sync marker at the end of every frame. The marker only matters when the ring
 * wraps around and wants to reuse old space, so one marker per sixteenth of the ring is enough. Markers the ring asks
 * for itself when it runs out of space are left alone.
 */
@Pseudo
@Mixin(targets = "com.gtnewhorizons.angelica.glsm.streaming.GlStreamingRing", remap = false)
public abstract class MixinGlStreamingRing_BatchSync {

    @Shadow
    private int pendingBytes;

    @Shadow
    @Final
    private int capacity;

    @Unique
    private boolean clientm$frameEnd;

    @Inject(method = "endFrame", at = @At("HEAD"), require = 0)
    private void clientm$markFrameEnd(CallbackInfo ci) {
        clientm$frameEnd = true;
    }

    @Inject(method = "fence", at = @At("HEAD"), cancellable = true, require = 0)
    private void clientm$skipSmallFence(CallbackInfo ci) {
        if (!clientm$frameEnd) return;
        clientm$frameEnd = false;
        if (pendingBytes < (capacity >>> 4) && FrameSubmitModule.batchSync()) ci.cancel();
    }
}
