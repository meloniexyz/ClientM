package com.clientm.mixins;

import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S07PacketRespawn;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.clientm.render.ChunkSafetyNet;

/** Tells the chunk safety net about every respawn (dimension change or "dimension swap"). */
@Mixin(NetHandlerPlayClient.class)
public abstract class MixinNetHandlerPlayClient_Respawn {

    @Inject(method = "handleRespawn", at = @At("TAIL"))
    private void clientm$respawned(S07PacketRespawn packet, CallbackInfo ci) {
        ChunkSafetyNet.respawned();
    }
}
