package com.clientm.mixins;

import net.minecraft.client.renderer.entity.RendererLivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;

import com.clientm.module.impl.GlintModule;

/**
 * Glint colour for worn armour. The first colour call after the glint texture bind is a neutral grey; the second
 * (ordinal 1) is the purple tint. See {@link MixinItemRenderer_GlintColor}.
 */
@Mixin(RendererLivingEntity.class)
public abstract class MixinRendererLivingEntity_GlintColor {

    private static final String DO_RENDER = "doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V";
    private static final String GLINT = "Lnet/minecraft/client/renderer/entity/RendererLivingEntity;RES_ITEM_GLINT:Lnet/minecraft/util/ResourceLocation;";

    @Redirect(
        method = DO_RENDER,
        slice = @Slice(from = @At(value = "FIELD", target = GLINT)),
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V", ordinal = 1, remap = false),
        require = 0)
    private void clientm$tintGlint(float red, float green, float blue, float alpha) {
        GlintModule.color(red, green, blue, alpha);
    }

    @Redirect(
        method = DO_RENDER,
        slice = @Slice(from = @At(value = "FIELD", target = GLINT)),
        at = @At(
            value = "INVOKE",
            target = "Lcom/gtnewhorizons/angelica/glsm/GLStateManager;glColor4f(FFFF)V",
            ordinal = 1,
            remap = false),
        require = 0)
    private void clientm$tintGlintGlsm(float red, float green, float blue, float alpha) {
        GlintModule.color(red, green, blue, alpha);
    }

    /** The glint loop on worn armour runs "k < 2"; this is that 2. */
    @ModifyConstant(
        method = DO_RENDER,
        slice = @Slice(from = @At(value = "FIELD", target = GLINT)),
        constant = @Constant(intValue = 2, ordinal = 0),
        require = 0)
    private int clientm$glintLayers(int layers) {
        return GlintModule.armourLayers();
    }
}
