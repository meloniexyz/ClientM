package com.clientm.mixins;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.world.IBlockAccess;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.clientm.module.impl.HideFoliageModule;

/** Skips drawing tall grass and similar plants when the Hide Foliage module is on. */
@Mixin(RenderBlocks.class)
public abstract class MixinRenderBlocks_HideFoliage {

    @Shadow
    public IBlockAccess blockAccess;

    @Inject(method = "renderBlockByRenderType", at = @At("HEAD"), cancellable = true, require = 0)
    private void clientm$hideFoliage(Block block, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (HideFoliageModule.hides(block, blockAccess, x, y, z)) cir.setReturnValue(false);
    }
}
