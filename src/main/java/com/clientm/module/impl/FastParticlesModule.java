package com.clientm.module.impl;

import com.clientm.module.Category;
import com.clientm.module.Module;

/**
 * Every moving particle asks the world, twenty times a second, for all entities near it, only to find out whether a
 * boat or a minecart is in its way. With this on, particles skip that question; they still collide with blocks.
 * The hook is in {@code MixinWorld_ParticleCollision}.
 */
public class FastParticlesModule extends Module {

    private static FastParticlesModule instance;

    public FastParticlesModule() {
        super("fastparticles", "Fast Particles", Category.PERFORMANCE, true);
        instance = this;
    }

    @Override
    public String description() {
        return "Particles no longer search for boats and minecarts to bump into";
    }

    public static boolean active() {
        FastParticlesModule m = instance;
        return m != null && m.enabled;
    }
}
