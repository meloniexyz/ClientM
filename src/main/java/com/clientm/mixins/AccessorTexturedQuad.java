package com.clientm.mixins;

import net.minecraft.client.model.TexturedQuad;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TexturedQuad.class)
public interface AccessorTexturedQuad {

    @Accessor("invertNormal")
    boolean clientm$invertNormal();
}
