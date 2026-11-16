package com.clientm.mixins;

import net.minecraft.client.model.ModelRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.clientm.render.ModelBatch;

/** While a model batch is open, a limb adds its faces to the batch and skips its own draw. */
@Mixin(ModelRenderer.class)
public abstract class MixinModelRenderer_Batch {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void clientm$addToBatch(float scale, CallbackInfo ci) {
        if (ModelBatch.active()) {
            ModelBatch.add((ModelRenderer) (Object) this, scale);
            ci.cancel();
        }
    }
}
