package com.clientm.mixins;

import java.util.Collections;
import java.util.List;

import net.minecraft.client.particle.EntityFX;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.clientm.module.impl.FastParticlesModule;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

/** Particles only need block collisions; the search for entities they could bump into is skipped for them. */
@Mixin(World.class)
public abstract class MixinWorld_ParticleCollision {

    @WrapOperation(
        method = "getCollidingBoundingBoxes",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;getEntitiesWithinAABBExcludingEntity(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/AxisAlignedBB;)Ljava/util/List;"),
        require = 0)
    private List<?> clientm$noEntitySearchForParticles(World world, Entity mover, AxisAlignedBB box,
        Operation<List<?>> original) {
        if (mover instanceof EntityFX && FastParticlesModule.active()) return Collections.emptyList();
        return original.call(world, mover, box);
    }
}
