package com.clientm.mixins;

import java.awt.image.BufferedImage;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.data.AnimationMetadataSection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.clientm.module.impl.ClearGlassModule;

/** Lets the Clear Glass module edit the glass image just before it goes into the block texture atlas. */
@Mixin(TextureAtlasSprite.class)
public abstract class MixinTextureAtlasSprite_ClearGlass {

    @Inject(method = "loadSprite", at = @At("HEAD"), require = 0)
    private void clientm$clearGlass(BufferedImage[] images, AnimationMetadataSection animation, boolean anisotropic,
        CallbackInfo ci) {
        ClearGlassModule.process(((TextureAtlasSprite) (Object) this).getIconName(), images);
    }
}
