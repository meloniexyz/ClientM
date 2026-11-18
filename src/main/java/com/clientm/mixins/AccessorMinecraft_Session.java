package com.clientm.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Session;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the account switcher replace the signed-in account while the game runs. */
@Mixin(Minecraft.class)
public interface AccessorMinecraft_Session {

    @Mutable
    @Accessor("session")
    void clientm$setSession(Session session);
}
