package com.clientm.mixins;

import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBiped;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.clientm.render.ModelBatch;

/**
 * Opens a model batch around the limbs of an adult humanoid model. Baby models resize the head through OpenGL
 * between limbs, so they keep the vanilla path.
 */
@Mixin(ModelBiped.class)
public abstract class MixinModelBiped_Batch extends ModelBase {

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void clientm$beginBatch(CallbackInfo ci) {
        if (!this.isChild) ModelBatch.begin();
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void clientm$endBatch(CallbackInfo ci) {
        ModelBatch.end();
    }
}
