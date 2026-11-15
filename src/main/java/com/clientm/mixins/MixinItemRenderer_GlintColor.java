package com.clientm.mixins;

import net.minecraft.client.renderer.ItemRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;

import com.clientm.module.impl.GlintModule;

/**
 * Recolours the glint on held items by rewriting the colour call that follows the glint texture bind. Two variants
 * exist because Angelica rewrites GL11 calls to its own state manager; whichever form is present when the mixin is
 * applied gets matched and the other is skipped (require = 0).
 */
@Mixin(ItemRenderer.class)
public abstract class MixinItemRenderer_GlintColor {

    private static final String RENDER_ITEM = "renderItem(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/item/ItemStack;ILnet/minecraftforge/client/IItemRenderer$ItemRenderType;)V";
    private static final String GLINT = "Lnet/minecraft/client/renderer/ItemRenderer;RES_ITEM_GLINT:Lnet/minecraft/util/ResourceLocation;";

    @Redirect(
        method = RENDER_ITEM,
        slice = @Slice(from = @At(value = "FIELD", target = GLINT)),
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V", ordinal = 0, remap = false),
        require = 0)
    private void clientm$tintGlint(float red, float green, float blue, float alpha) {
        GlintModule.color(red, green, blue, alpha);
    }

    @Redirect(
        method = RENDER_ITEM,
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
