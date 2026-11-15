package com.clientm.module.impl;

import java.lang.reflect.Field;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.InventoryEffectRenderer;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import com.clientm.ClientM;
import com.clientm.module.Category;
import com.clientm.module.Module;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;

/**
 * The game moves the inventory to the right whenever a potion effect is active. This puts it back in the middle of
 * the screen, so slots are always in the same place. It runs once when the inventory opens.
 */
public class CentredInventoryModule extends Module {

    private Field guiLeft;
    private Field xSize;

    public CentredInventoryModule() {
        super("centredinventory", "Centred Inventory", Category.VISUAL, true);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public String description() {
        return "Potion effects no longer shift the inventory";
    }

    @SubscribeEvent
    public void onInitGui(GuiScreenEvent.InitGuiEvent.Post event) {
        if (!enabled || !(event.gui instanceof InventoryEffectRenderer)) return;
        try {
            if (guiLeft == null) {
                guiLeft = ReflectionHelper.findField(GuiContainer.class, "guiLeft", "field_147003_i");
                xSize = ReflectionHelper.findField(GuiContainer.class, "xSize", "field_146999_f");
            }
            guiLeft.setInt(event.gui, (event.gui.width - xSize.getInt(event.gui)) / 2);
        } catch (Exception e) {
            ClientM.LOG.error("Could not centre the inventory", e);
        }
    }
}
