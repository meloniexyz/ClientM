package com.clientm.mixins;

import java.util.Queue;

import net.minecraft.network.NetworkManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets Hit Fix look at the packets the server has sent that the game has not handled yet. */
@Mixin(NetworkManager.class)
public interface AccessorNetworkManager {

    @Accessor("receivedPacketsQueue")
    Queue<?> clientm$receivedPackets();
}
