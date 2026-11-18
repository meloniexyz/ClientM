package com.clientm.mixins;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenResourcePacks;
import net.minecraft.client.resources.ResourcePackListEntry;
import net.minecraft.client.resources.ResourcePackListEntryFound;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.clientm.gui.PackFolderEntry;

/**
 * Shows the available resource packs folder by folder, the way they are kept on disk: the top of the resourcepacks
 * folder first, with each folder of packs as an entry that opens it, and a back entry inside folders. Selecting packs
 * works as before; the selected side always shows every selected pack, wherever it lives.
 */
@Mixin(GuiScreenResourcePacks.class)
public abstract class MixinGuiScreenResourcePacks_Folders extends GuiScreen implements PackFolderEntry.Opener {

    @Shadow
    private List<ResourcePackListEntry> field_146966_g;

    @Shadow
    private List<ResourcePackListEntry> field_146969_h;

    /** Every available (not selected) pack, wherever it lives. The visible list is the part in the open folder. */
    @Unique
    private final List<ResourcePackListEntry> clientm$all = new ArrayList<>();

    @Unique
    private File clientm$root;

    @Unique
    private File clientm$folder;

    @Inject(method = "initGui", at = @At("RETURN"), require = 0)
    private void clientm$showFolders(CallbackInfo ci) {
        clientm$root = mc.getResourcePackRepository()
            .getDirResourcepacks()
            .getAbsoluteFile();
        clientm$all.clear();
        clientm$all.addAll(field_146966_g);
        // Reopening the screen (for example after the window is resized) keeps the folder that was open.
        if (clientm$folder == null || !clientm$folder.isDirectory()) clientm$folder = clientm$root;
        clientm$rebuild();
    }

    @Override
    public void clientm$openFolder(File folder) {
        clientm$folder = folder;
        clientm$rebuild();
    }

    @Unique
    private void clientm$rebuild() {
        // Packs moved between the two sides since the last rebuild.
        clientm$all.removeAll(field_146969_h);
        for (ResourcePackListEntry e : field_146966_g) {
            if (e instanceof ResourcePackListEntryFound && !clientm$all.contains(e)) clientm$all.add(0, e);
        }

        GuiScreenResourcePacks self = (GuiScreenResourcePacks) (Object) this;
        String here = clientm$folder.getAbsolutePath();
        List<ResourcePackListEntry> packs = new ArrayList<>();
        // Sub-folder of the open folder -> number of packs somewhere inside it.
        Map<String, int[]> folders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, File> folderFiles = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ResourcePackListEntry e : clientm$all) {
            File file = clientm$fileOf(e);
            if (file == null) continue;
            File parent = file.getAbsoluteFile()
                .getParentFile();
            if (parent == null) continue;
            String parentPath = parent.getAbsolutePath();
            if (parentPath.equalsIgnoreCase(here)) {
                packs.add(e);
            } else if (parentPath.toLowerCase(Locale.ROOT)
                .startsWith(here.toLowerCase(Locale.ROOT) + File.separator)) {
                    // The first folder below the open one on the way to this pack.
                    String rest = parentPath.substring(here.length() + 1);
                    int cut = rest.indexOf(File.separatorChar);
                    String name = cut < 0 ? rest : rest.substring(0, cut);
                    folders.computeIfAbsent(name, k -> new int[1])[0]++;
                    folderFiles.put(name, new File(clientm$folder, name));
                }
        }

        field_146966_g.clear();
        if (!here.equalsIgnoreCase(clientm$root.getAbsolutePath())) {
            File up = clientm$folder.getParentFile();
            String upName = up == null || up.getAbsolutePath()
                .equalsIgnoreCase(clientm$root.getAbsolutePath()) ? "resource packs" : up.getName();
            field_146966_g.add(new PackFolderEntry(self, up == null ? clientm$root : up, true, "Up to " + upName));
        }
        for (Map.Entry<String, int[]> f : folders.entrySet()) {
            int n = f.getValue()[0];
            field_146966_g
                .add(new PackFolderEntry(self, folderFiles.get(f.getKey()), false, n + (n == 1 ? " pack" : " packs")));
        }
        field_146966_g.addAll(packs);
    }

    @Unique
    private static File clientm$fileOf(ResourcePackListEntry e) {
        if (!(e instanceof ResourcePackListEntryFound)) return null;
        return ((AccessorRepositoryEntry) ((ResourcePackListEntryFound) e).func_148318_i()).clientm$file();
    }
}
