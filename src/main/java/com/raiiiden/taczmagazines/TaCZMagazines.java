package com.raiiiden.taczmagazines;

import com.raiiiden.taczmagazines.capability.GunMagazineCapability;
import com.raiiiden.taczmagazines.capability.GunMagazineProvider;
import com.raiiiden.taczmagazines.command.MagazineCommands;
import com.raiiiden.taczmagazines.item.MagazineRegistrar;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
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

    // Register magazines
    MagazineRegistrar.register();

    // Event bus listeners
    FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
    FMLJavaModLoadingContext.get().getModEventBus().addListener(this::registerCapabilities);
    MinecraftForge.EVENT_BUS.addListener(this::registerCommands);
  }

  private void commonSetup(FMLCommonSetupEvent event) {
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
}