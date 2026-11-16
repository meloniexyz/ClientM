package com.clientm.mixins;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.clientm.module.impl.LightCacheModule;

/**
 * Keeps the brightness an entity or particle was given for the rest of the game tick. Both world lookups inside
 * {@code getBrightnessForRender} are answered from the kept value, so no objects are made per call.
 */
@Mixin(Entity.class)
public abstract class MixinEntity_LightCache {

    @Unique
    private int clientm$lightTick;

    @Unique
    private int clientm$light;

    @Redirect(
        method = "getBrightnessForRender",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;blockExists(III)Z"),
        require = 0)
    private boolean clientm$skipChunkCheck(World world, int x, int y, int z) {
        int tick = LightCacheModule.tick();
        if (tick != 0 && tick == clientm$lightTick) return true;
        return world.blockExists(x, y, z);
    }

    @Redirect(
        method = "getBrightnessForRender",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getLightBrightnessForSkyBlocks(IIII)I"),
        require = 0)
    private int clientm$lightOncePerTick(World world, int x, int y, int z, int min) {
        int tick = LightCacheModule.tick();
        if (tick == 0) return world.getLightBrightnessForSkyBlocks(x, y, z, min);
        if (tick != clientm$lightTick) {
            clientm$light = world.getLightBrightnessForSkyBlocks(x, y, z, min);
            clientm$lightTick = tick;
        }
        return clientm$light;
    }
}
