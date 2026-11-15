package com.clientm.mixins;

import net.minecraft.client.renderer.entity.RenderItem;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;

import com.clientm.module.impl.GlintModule;

/** Glint colour for dropped items and items drawn in inventories. See {@link MixinItemRenderer_GlintColor}. */
@Mixin(RenderItem.class)
public abstract class MixinRenderItem_GlintColor {

    private static final String DROPPED = "renderDroppedItem(Lnet/minecraft/entity/item/EntityItem;Lnet/minecraft/util/IIcon;IFFFFI)V";
    private static final String GUI = "renderItemAndEffectIntoGUI";
    private static final String EFFECT = "renderEffect";
    private static final String GLINT = "Lnet/minecraft/client/renderer/entity/RenderItem;RES_ITEM_GLINT:Lnet/minecraft/util/ResourceLocation;";

    @Redirect(
        method = { DROPPED, GUI, EFFECT },
        slice = @Slice(from = @At(value = "FIELD", target = GLINT)),
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V", ordinal = 0, remap = false),
        require = 0)
    private void clientm$tintGlint(float red, float green, float blue, float alpha) {
        GlintModule.color(red, green, blue, alpha);
    }

    @Redirect(
        method = { DROPPED, GUI, EFFECT },
        slice = @Slice(from = @At(value = "FIELD", target = GLINT)),
        at = @At(
            value = "INVOKE",
            target = "Lcom/gtnewhorizons/angelica/glsm/GLStateManager;glColor4f(FFFF)V",
            ordinal = 0,
            remap = false),
        require = 0)
    private void clientm$tintGlintGlsm(float red, float green, float blue, float alpha) {
        GlintModule.color(red, green, blue, alpha);
    }
}
