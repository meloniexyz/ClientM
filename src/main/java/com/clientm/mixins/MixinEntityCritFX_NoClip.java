package com.clientm.mixins;

import net.minecraft.client.particle.EntityCritFX;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.clientm.module.impl.FastParticlesModule;

/**
 * Fast Particles' "crit particles skip block collision": a hit spawns dozens of crit particles, and each one asked the
 * world for the blocks around it every tick. With the option on they fly through blocks instead (they live under a
 * second), which takes most of the particle work out of a fight's game ticks.
 */
@Mixin(EntityCritFX.class)
public abstract class MixinEntityCritFX_NoClip extends EntityFX {

    private MixinEntityCritFX_NoClip(World world, double x, double y, double z) {
        super(world, x, y, z);
    }

    @Inject(method = "<init>*", at = @At("TAIL"))
    private void clientm$skipBlocks(CallbackInfo ci) {
        if (FastParticlesModule.critsSkipBlocks()) this.noClip = true;
    }
}
