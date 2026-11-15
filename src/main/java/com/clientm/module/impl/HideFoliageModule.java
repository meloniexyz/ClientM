package com.clientm.module.impl;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDeadBush;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.BlockFlower;
import net.minecraft.block.BlockTallGrass;
import net.minecraft.world.IBlockAccess;

import com.clientm.module.Category;
import com.clientm.module.Module;
import com.clientm.setting.BoolSetting;

/**
 * Leaves tall grass, ferns and dead bushes (optionally flowers too) out of the world. They are skipped while a chunk
 * is being built, so they cost nothing at all afterwards: fewer triangles, and a clearer view in fights.
 * The blocks are still there; only their drawing is skipped. See MixinRenderBlocks_HideFoliage.
 */
public class HideFoliageModule extends Module {

    private static HideFoliageModule instance;
    // Read from the chunk-building threads.
    private static volatile boolean active;
    private static volatile boolean hideFlowers;

    private final BoolSetting flowers = add(new BoolSetting("flowers", "Also hide flowers", false));

    public HideFoliageModule() {
        super("hidefoliage", "Hide Foliage", Category.PERFORMANCE, false);
        instance = this;
    }

    @Override
    public String description() {
        return "No tall grass, ferns or dead bushes";
    }

    @Override
    public void onEnable() {
        sync();
    }

    @Override
    public void onDisable() {
        sync();
    }

    @Override
    public void onSettingsChanged() {
        sync();
    }

    /** Used by the video presets. */
    public static void set(boolean hide) {
        if (instance != null) instance.setEnabled(hide);
    }

    private void sync() {
        if (active == enabled && hideFlowers == flowers.value) return;
        active = enabled;
        hideFlowers = flowers.value;
        // Chunks already built still contain the old plants, so rebuild them.
        if (mc.theWorld != null && mc.renderGlobal != null) mc.renderGlobal.loadRenderers();
    }

    /** True if this block should not be drawn. Called for every block of every chunk build, so it stays cheap. */
    public static boolean hides(Block block, IBlockAccess world, int x, int y, int z) {
        if (!active) return false;
        if (block instanceof BlockTallGrass || block instanceof BlockDeadBush) return true;
        if (block instanceof BlockDoublePlant) {
            int meta = world.getBlockMetadata(x, y, z);
            if ((meta & 8) != 0) meta = world.getBlockMetadata(x, y - 1, z); // top half: the type is on the bottom half
            int type = meta & 7;
            return type == 2 || type == 3 || hideFlowers; // 2 = double tall grass, 3 = large fern
        }
        return hideFlowers && block instanceof BlockFlower;
    }
}
