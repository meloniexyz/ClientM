package com.clientm.mixins;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets Hit Fix carry out an attack click or a right-click on the frame it happens. */
@Mixin(Minecraft.class)
public interface AccessorMinecraft_Click {

    @Invoker("func_147116_af")
    void clientm$attackClick();

    // func_147121_ag: the right-click action (use the held item, place a block or interact)
    @Invoker("func_147121_ag")
    void clientm$useClick();
}
