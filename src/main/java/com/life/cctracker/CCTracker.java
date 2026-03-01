package com.life.cctracker;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;

@Mod(modid = CCTracker.MODID, version = CCTracker.VERSION)
public class CCTracker
{
    public static final String MODID = "cctracker";
    public static final String VERSION = "1.0";

    private EnderChestTracker tracker;

    @EventHandler
    public void init(FMLInitializationEvent event)
    {
        tracker = new EnderChestTracker();
        MinecraftForge.EVENT_BUS.register(tracker);
        if (FMLCommonHandler.instance().getEffectiveSide() == Side.CLIENT) {
            EnderChestTracker.setClientTracker(tracker);
            ToggleCCCommand.register(tracker);
            ClearCCCommand.register(tracker);
        }
        MinecraftForge.EVENT_BUS.register(new EnderChestPacketFilter());
        if (FMLCommonHandler.instance().getEffectiveSide() == Side.CLIENT) {
            FMLCommonHandler.instance().bus().register(new ClientEnderChestPacketBlocker());
        }
    }
}
