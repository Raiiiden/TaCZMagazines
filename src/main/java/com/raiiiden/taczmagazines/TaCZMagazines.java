package com.raiiiden.taczmagazines;

import com.raiiiden.taczmagazines.capability.GunMagazineCapability;
import com.raiiiden.taczmagazines.command.MagazineCommands;
import com.raiiiden.taczmagazines.config.MagazineConfig;
import com.raiiiden.taczmagazines.item.MagazineRegistrar;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.raiiiden.taczmagazines.network.PacketHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(TaCZMagazines.MODID)
public class TaCZMagazines {
  public static final String MODID = "taczmagazines";
  public static final Logger LOGGER = LogManager.getLogger();

  public TaCZMagazines() {
    LOGGER.info("[{}] TaCZ Magazines mod initializing...", MODID);

    // Register config
    MagazineConfig.register();

    // Register magazines
    MagazineRegistrar.register();

    // Event bus listeners
    FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
    FMLJavaModLoadingContext.get().getModEventBus().addListener(this::registerCapabilities);
    MinecraftForge.EVENT_BUS.addListener(this::registerCommands);
    MinecraftForge.EVENT_BUS.addListener(this::onDatapackSync);
  }

  private void commonSetup(FMLCommonSetupEvent event) {
    event.enqueueWork(PacketHandler::register);
    LOGGER.info("[{}] Common setup complete", MODID);
  }

  @SubscribeEvent
  public void registerCapabilities(RegisterCapabilitiesEvent event) {
    event.register(GunMagazineCapability.class);
    LOGGER.info("[{}] Registered gun magazine capability", MODID);
  }

  private void registerCommands(RegisterCommandsEvent event) {
    event.getDispatcher().register(MagazineCommands.register());
    LOGGER.info("[{}] Registered magazine commands", MODID);
  }

  @SubscribeEvent
  public void onDatapackSync(OnDatapackSyncEvent event) {
    // Discover magazine families when datapacks load
    MagazineFamilySystem.discoverMagazineFamilies();
    LOGGER.info("[{}] Magazine families discovered", MODID);

    // Invalidate the magazine render cache on the client so it picks up updated gun models.
    // Using DistExecutor to safely call client-only code without crashing on the server.
    DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
            () -> com.raiiiden.taczmagazines.client.MagazineItemRenderer::invalidateCache);
  }
}