package com.raiiiden.taczmagazines.client;

import com.raiiiden.taczmagazines.TaCZMagazines;
import com.raiiiden.taczmagazines.network.OpenSelectorPacket;
import com.raiiiden.taczmagazines.network.PacketHandler;
import com.raiiiden.taczmagazines.network.SelectMagazinePacket;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.input.ReloadKey;
import com.tacz.guns.util.InputExtraCheck;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = TaCZMagazines.MODID, value = Dist.CLIENT)
public class ClientReloadKeyHandler {

    private static final long HOLD_THRESHOLD_MS = 400;

    private static long keyDownAt = 0;
    private static boolean keyDown = false;
    private static boolean inSelectorMode = false;
    private static boolean cancelled = false;
    private static boolean serverBlocked = false;

    public static boolean isSelectorOpen() {
        return inSelectorMode;
    }

    public static boolean onReloadKeyPressed() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.isSpectator()) return false;
        ItemStack gun = player.getMainHandItem();
        if (!(gun.getItem() instanceof IGun iGun)) return false;
        if (iGun.useInventoryAmmo(gun)) return false;

        // Only intercept for magazine-type guns that have a family registered.
        // Non-magazine guns (shotguns, etc.) should pass through to TaCZ normally.
        ResourceLocation gunId = iGun.getGunId(gun);
        com.tacz.guns.resource.index.CommonGunIndex gunIndex =
                com.tacz.guns.api.TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        if (gunIndex == null) return false;
        if (!gunIndex.getGunData().getReloadData().getType()
                .equals(com.tacz.guns.resource.pojo.data.gun.FeedType.MAGAZINE)) return false;
        if (com.raiiiden.taczmagazines.magazine.MagazineFamilySystem.getFamilyForGun(gunId) == null) return false;

        keyDown = true;
        keyDownAt = System.currentTimeMillis();
        inSelectorMode = false;
        cancelled = false;
        serverBlocked = true;

        PacketHandler.CHANNEL.sendToServer(new OpenSelectorPacket(true));
        return true;
    }

    public static void onReloadKeyReleased() {
        if (!keyDown) return;
        keyDown = false;

        if (cancelled) {
            cancelled = false;
            if (serverBlocked) {
                serverBlocked = false;
                PacketHandler.CHANNEL.sendToServer(new OpenSelectorPacket(false));
            }
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            if (serverBlocked) {
                serverBlocked = false;
                PacketHandler.CHANNEL.sendToServer(new OpenSelectorPacket(false));
            }
            return;
        }

        if (inSelectorMode) {
            inSelectorMode = false;
            int selectedSlot = MagazineSelectorOverlay.getSelectedSlot();
            MagazineSelectorOverlay.close();
            serverBlocked = false;
            if (selectedSlot >= 0) {
                // SelectMagazinePacket unblocks atomically + writes slot tag.
                PacketHandler.CHANNEL.sendToServer(new SelectMagazinePacket(selectedSlot));
                IClientPlayerGunOperator.fromLocalPlayer(player).reload();
            } else {
                PacketHandler.CHANNEL.sendToServer(new OpenSelectorPacket(false));
            }
        } else {
            // Single tap — send SelectMagazinePacket with slot = -1 to atomically
            // unblock the server and then immediately trigger reload. This prevents
            // the race condition where OpenSelectorPacket(false) hasn't been processed
            // yet when canReload fires server-side.
            serverBlocked = false;
            PacketHandler.CHANNEL.sendToServer(new SelectMagazinePacket(-1));
            IClientPlayerGunOperator.fromLocalPlayer(player).reload();
        }
    }

    public static void onRightClickCancel() {
        if (!inSelectorMode) return;
        cancelled = true;
        inSelectorMode = false;
        MagazineSelectorOverlay.close();
        // Keep server blocked until R is released.
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (inSelectorMode) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.keyUse.isDown()) {
                onRightClickCancel();
            }
            if (!InputExtraCheck.isInGame()) {
                inSelectorMode = false;
                MagazineSelectorOverlay.close();
                keyDown = false;
                if (serverBlocked) {
                    serverBlocked = false;
                    PacketHandler.CHANNEL.sendToServer(new OpenSelectorPacket(false));
                }
            }
            return;
        }

        if (!keyDown) return;
        if (!InputExtraCheck.isInGame()) {
            keyDown = false;
            return;
        }

        if (!ReloadKey.RELOAD_KEY.isDown()) {
            onReloadKeyReleased();
            return;
        }

        // Don't reopen selector if player right-click cancelled this hold.
        if (cancelled) return;

        long heldMs = System.currentTimeMillis() - keyDownAt;
        if (heldMs >= HOLD_THRESHOLD_MS) {
            inSelectorMode = true;
            // Server is already blocked from key press — no need to send again.
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                MagazineSelectorOverlay.open(player);
            }
        }
    }
}