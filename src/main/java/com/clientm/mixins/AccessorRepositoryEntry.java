package com.clientm.mixins;

import java.io.File;

import net.minecraft.client.resources.ResourcePackRepository;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The file or folder a listed resource pack comes from, so the pack screen can sort packs into their folders. */
@Mixin(ResourcePackRepository.Entry.class)
public interface AccessorRepositoryEntry {

    @Accessor("resourcePackFile")
    File clientm$file();
}
