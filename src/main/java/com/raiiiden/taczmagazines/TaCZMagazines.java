package com.raiiiden.taczmagazines;

import com.raiiiden.taczmagazines.capability.GunMagazineCapability;
import com.raiiiden.taczmagazines.command.MagazineCommands;
import com.raiiiden.taczmagazines.config.FamilyConfigManager;
import com.raiiiden.taczmagazines.config.GunOverrideConfig;
import com.raiiiden.taczmagazines.config.MechanicsConfig;
import com.raiiiden.taczmagazines.crafting.GunsmithIntegration;
import com.raiiiden.taczmagazines.item.MagazineRegistrar;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.raiiiden.taczmagazines.network.PacketHandler;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

@Mod(TaCZMagazines.MODID)
public class TaCZMagazines {
  public static final String MODID = "taczmagazines";
  public static final Logger LOGGER = LogManager.getLogger();

  public TaCZMagazines() {
    LOGGER.info("[{}] TaCZ Magazines mod initializing...", MODID);

    // Register configs
    FamilyConfigManager.register();
    GunOverrideConfig.register();
    MechanicsConfig.register();

    // Register magazines
    MagazineRegistrar.register();

    // Event bus listeners
    FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
    FMLJavaModLoadingContext.get().getModEventBus().addListener(this::registerCapabilities);
    MinecraftForge.EVENT_BUS.addListener(this::registerCommands);
    MinecraftForge.EVENT_BUS.addListener(this::onDatapackSync);
    MinecraftForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);

    // Register the client-side RecipesUpdatedEvent listener at startup so it fires even
    // when connecting to a dedicated server (OnDatapackSyncEvent only fires server-side).
    DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
            () -> () -> MinecraftForge.EVENT_BUS.addListener(
                    com.raiiiden.taczmagazines.client.ClientRecipeSorter::onRecipesUpdated));

    // Register client-side commands (run in the client dispatcher, safe on dedicated servers).
    DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
            () -> () -> MinecraftForge.EVENT_BUS.addListener(
                    com.raiiiden.taczmagazines.command.ClientCommands::onRegisterClientCommands));
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
    // 1. Discover shared families from gun data
    MagazineFamilySystem.discoverMagazineFamilies();
    // 2. Apply gun overrides/exclusions and isolated-gun private families
    GunOverrideConfig.apply();
    // 3. Apply family model overrides (which gun's render each family uses)
    FamilyConfigManager.load();
    LOGGER.info("[{}] Magazine families discovered", MODID);

    // Invalidate the magazine render cache on the client so it picks up updated gun models.
    // Using DistExecutor to safely call client-only code without crashing on the server.
    DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
            () -> com.raiiiden.taczmagazines.client.MagazineItemRenderer::invalidateCache);


    // Inject "Magazines" tab and recipes into the gun_smith_table.
    // ServerLifecycleHooks.getCurrentServer() returns null on the logical client during
    // singleplayer before the integrated server starts, so guard against null.
    GunsmithIntegration.setup(ServerLifecycleHooks.getCurrentServer());
  }

  // Sends red chat messages to any player who logs in while there are config errors.
  // Errors persist until the config is fixed and datapacks are reloaded (F3+T).
  private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
    List<String> errors = new ArrayList<>();
    errors.addAll(GunOverrideConfig.getErrors());
    errors.addAll(FamilyConfigManager.getErrors());
    if (errors.isEmpty()) return;

    net.minecraft.world.entity.player.Player player = event.getEntity();
    player.sendSystemMessage(Component.literal(
        "§c[TaCZMagazines] Config errors — fix and press F3+T to reload:"));
    for (String error : errors) {
        player.sendSystemMessage(Component.literal("§c  • " + error));
    }
  }
}