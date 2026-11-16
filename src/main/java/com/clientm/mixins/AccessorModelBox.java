package com.clientm.mixins;

import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.TexturedQuad;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ModelBox.class)
public interface AccessorModelBox {

    @Accessor("quadList")
    TexturedQuad[] clientm$quads();
}
