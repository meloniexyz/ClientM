package com.clientm.mixins;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.resources.ResourcePackRepository;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.7 only lists packs that sit directly in the resourcepacks folder. Packs are often kept in folders of their own
 * ("PvP packs/Blue 128x.zip"), so folders that are not a pack themselves (no pack.mcmeta) are looked into as well, up
 * to four levels deep, and every pack found there is listed as if it were at the top.
 */
@Mixin(ResourcePackRepository.class)
public abstract class MixinResourcePackRepository_Folders {

    private static final int DEPTH = 4;

    @Shadow
    @Final
    private File dirResourcepacks;

    @Inject(method = "getResourcePackFiles", at = @At("HEAD"), cancellable = true, require = 0)
    private void clientm$packsInFolders(CallbackInfoReturnable<List<File>> cir) {
        if (!dirResourcepacks.isDirectory()) return;
        List<File> found = new ArrayList<>();
        // Pack names must stay unique: the selected packs are saved by name. The first one found wins.
        Set<String> names = new HashSet<>();
        clientm$collect(dirResourcepacks, 0, found, names);
        cir.setReturnValue(found);
    }

    private static void clientm$collect(File dir, int depth, List<File> found, Set<String> names) {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files);
        // Packs at this level first, then the folders, so a top-level pack keeps its name.
        List<File> folders = new ArrayList<>();
        for (File f : files) {
            boolean zip = f.isFile() && f.getName()
                .endsWith(".zip");
            boolean folderPack = f.isDirectory() && new File(f, "pack.mcmeta").isFile();
            if (zip || folderPack) {
                if (names.add(f.getName())) found.add(f);
            } else if (f.isDirectory() && depth < DEPTH) {
                folders.add(f);
            }
        }
        for (File f : folders) clientm$collect(f, depth + 1, found, names);
    }
}
