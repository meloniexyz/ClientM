package com.clientm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.clientm.module.ModuleManager;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.relauncher.Side;

@Mod(
    modid = ClientM.MODID,
    version = Tags.VERSION,
    name = "ClientM",
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*")
public class ClientM {

    public static final String MODID = "clientm";
    public static final Logger LOG = LogManager.getLogger(MODID);

    public static ModuleManager modules;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        if (event.getSide() != Side.CLIENT) return;
        modules = new ModuleManager(new java.io.File(event.getModConfigurationDirectory(), "clientm.json"));
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (event.getSide() != Side.CLIENT) return;
        modules.init();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new com.clientm.gui.ClientMainMenu.Hook());
        if (com.clientm.debug.ScreenshotTour.requested()) {
            cpw.mods.fml.common.FMLCommonHandler.instance()
                .bus()
                .register(new com.clientm.debug.ScreenshotTour());
        }
        LOG.info(
            "ClientM {} loaded with {} modules",
            Tags.VERSION,
            modules.all()
                .size());
    }
}
