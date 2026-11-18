package com.clientm.gui;

import java.io.File;

import net.minecraft.client.gui.GuiScreenResourcePacks;
import net.minecraft.client.resources.ResourcePackListEntry;
import net.minecraft.util.ResourceLocation;

/**
 * A folder in the list of available resource packs. Clicking it shows the packs and folders inside; the "back" kind
 * goes up one level. Neither can be moved to the selected side.
 */
public class PackFolderEntry extends ResourcePackListEntry {

    /** Implemented by the pack screen (see MixinGuiScreenResourcePacks_Folders). */
    public interface Opener {

        void clientm$openFolder(File folder);
    }

    private static final ResourceLocation FOLDER = new ResourceLocation("clientm", "textures/gui/pack_folder.png");
    private static final ResourceLocation BACK = new ResourceLocation("clientm", "textures/gui/pack_back.png");

    private final GuiScreenResourcePacks screen;
    private final File folder;
    private final boolean back;
    private final String description;

    public PackFolderEntry(GuiScreenResourcePacks screen, File folder, boolean back, String description) {
        super(screen);
        this.screen = screen;
        this.folder = folder;
        this.back = back;
        this.description = description;
    }

    @Override
    protected String func_148311_a() {
        return description;
    }

    @Override
    protected String func_148312_b() {
        return back ? "§7← Back" : "§e" + folder.getName();
    }

    @Override
    protected void func_148313_c() {
        field_148317_a.getTextureManager()
            .bindTexture(back ? BACK : FOLDER);
    }

    /** No arrows: a folder cannot be selected as a pack. */
    @Override
    protected boolean func_148310_d() {
        return false;
    }

    @Override
    public boolean mousePressed(int index, int x, int y, int button, int relX, int relY) {
        ((Opener) screen).clientm$openFolder(folder);
        return true;
    }
}
