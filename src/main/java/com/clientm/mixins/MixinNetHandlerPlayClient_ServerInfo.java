package com.clientm.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.Entity;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraft.network.play.server.S19PacketEntityStatus;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.clientm.module.impl.HitMarkerModule;
import com.clientm.module.impl.ServerInfoModule;

/** Feeds the Ping & TPS display (server time updates) and the Hit Marker (entities reported hurt). */
@Mixin(NetHandlerPlayClient.class)
public abstract class MixinNetHandlerPlayClient_ServerInfo {

    @Inject(method = "handleTimeUpdate", at = @At("HEAD"))
    private void clientm$timeUpdate(S03PacketTimeUpdate packet, CallbackInfo ci) {
        ServerInfoModule.timeUpdate(packet.func_149366_c());
    }

    @Inject(method = "handleJoinGame", at = @At("HEAD"))
    private void clientm$joinGame(S01PacketJoinGame packet, CallbackInfo ci) {
        ServerInfoModule.reset();
    }

    @Inject(method = "handleEntityStatus", at = @At("HEAD"))
    private void clientm$entityStatus(S19PacketEntityStatus packet, CallbackInfo ci) {
        byte status = packet.func_149160_c();
        if (status != 2 && status != 3) return; // 2: was hurt, 3: died (the killing blow is a hit too)
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return;
        Entity entity = packet.func_149161_a(mc.theWorld);
        if (entity != null) HitMarkerModule.hurt(entity.getEntityId());
    }
}
