package com.clientm.mixins;

import net.minecraft.client.network.NetHandlerPlayClient;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.clientm.module.impl.HitFixModule;

/**
 * Hit Fix's enemy movement smoothing: when the server moves or teleports an entity, vanilla slides it to the new
 * position over 3 ticks (about 150 ms), so what you see and aim at trails where the server has it. Fewer ticks keep it
 * closer. Only the client's own display and aim change; nothing is sent differently.
 */
@Mixin(NetHandlerPlayClient.class)
public abstract class MixinNetHandlerPlayClient_Smoothing {

    @ModifyArg(
        method = { "handleEntityMovement", "handleEntityTeleport" },
        at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;setPositionAndRotation2(DDDFFI)V"),
        index = 5)
    private int clientm$smoothing(int ticks) {
        return HitFixModule.smoothingTicks(ticks);
    }
}
