package com.clientm.mixins;

import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.EntityLivingBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.clientm.module.impl.HitFixModule;

/**
 * In 1.7 a hit is handled at the start of a game tick and the hurt timer is counted down in that same tick, so the
 * first frame after a hit already shows the timer at 9: the camera snaps into its tilt. With Hit Fix the hit is
 * handled between ticks, so frames see the timer at 10 and the tilt grows in smoothly, as in 1.8. This reads a timer
 * that has not been counted down yet as one lower, which gives back the 1.7 snap without changing when the hit is
 * handled.
 */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer_HurtCam17 {

    @Redirect(
        method = "hurtCameraEffect",
        at = @At(value = "FIELD", target = "Lnet/minecraft/entity/EntityLivingBase;hurtTime:I", opcode = 180),
        require = 0)
    private int clientm$hurtTimeAs17(EntityLivingBase entity) {
        int time = entity.hurtTime;
        return time > 0 && time == entity.maxHurtTime && HitFixModule.hurtCamera17() ? time - 1 : time;
    }
}
