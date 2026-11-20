package com.clientm.mixins;

import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.clientm.module.impl.HitMarkerModule;

/** Remembers whom you last attacked, so the Hit Marker shows only for your own confirmed hits. */
@Mixin(PlayerControllerMP.class)
public abstract class MixinPlayerControllerMP_HitMarker {

    @Inject(method = "attackEntity", at = @At("HEAD"))
    private void clientm$attacked(EntityPlayer player, Entity target, CallbackInfo ci) {
        if (target != null) HitMarkerModule.attacked(target.getEntityId());
    }
}
