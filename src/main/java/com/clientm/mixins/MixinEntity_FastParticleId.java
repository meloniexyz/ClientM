package com.clientm.mixins;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.client.particle.EntityFX;
import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Every entity gets a random ID when it is created, from Java's cryptographically secure random generator. Particles
 * are entities too, and a fight spawns hundreds per second, so this showed up in every game tick. Particles get their
 * ID from a fast random generator instead (nothing reads a particle's ID); every other entity is unchanged.
 */
@Mixin(Entity.class)
public abstract class MixinEntity_FastParticleId {

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/util/UUID;randomUUID()Ljava/util/UUID;"))
    private UUID clientm$particleId() {
        if ((Object) this instanceof EntityFX) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            return new UUID(r.nextLong(), r.nextLong());
        }
        return UUID.randomUUID();
    }
}
