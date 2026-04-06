package com.raiiiden.taczmagazines.network;

import com.raiiiden.taczmagazines.item.MagazineItem;
import com.raiiiden.taczmagazines.magazine.MagazineFamilySystem;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// Transfers exactly one bullet to or from a magazine slot. Fired by the client loading-session ticker in tick-based mode.
public class BulletTransferPacket {

    private final int containerSlot;
    private final boolean unload;

    public BulletTransferPacket(int containerSlot, boolean unload) {
        this.containerSlot = containerSlot;
        this.unload        = unload;
    }

    // -------------------------------------------------------------------------

    public static void encode(BulletTransferPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.containerSlot);
        buf.writeBoolean(msg.unload);
    }

    public static BulletTransferPacket decode(FriendlyByteBuf buf) {
        return new BulletTransferPacket(buf.readVarInt(), buf.readBoolean());
    }

    public static void handle(BulletTransferPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            AbstractContainerMenu menu = player.containerMenu;
            if (menu == null || msg.containerSlot < 0 || msg.containerSlot >= menu.slots.size()) return;

            Slot slot = menu.slots.get(msg.containerSlot);
            ItemStack mag = slot.getItem();
            if (mag.isEmpty() || !(mag.getItem() instanceof MagazineItem magItem)) return;

            // If the slot holds a stack, split one off so only that single mag is modified.
            // IMPORTANT: extras are added to inventory AFTER the transfer so their NBT
            // differs from the now-modified slot mag — preventing the inventory merge
            // from folding them back into the same stack before we write new NBT.
            ItemStack extras = ItemStack.EMPTY;
            if (mag.getCount() > 1) {
                extras = mag.split(mag.getCount() - 1); // mag is now count=1 in-place
                slot.setChanged();
            }

            if (msg.unload) {
                handleUnload(player, menu, slot, mag, magItem);
            } else {
                handleLoad(player, menu, slot, mag, magItem);
            }

            // Give back the unmodified remainder only after NBT has changed on the slot mag.
            if (!extras.isEmpty()) {
                if (!player.getInventory().add(extras)) player.drop(extras, false);
            }

            menu.broadcastChanges();
        });
        ctx.get().setPacketHandled(true);
    }

    // ── Load one bullet: cursor → magazine ───────────────────────────────────

    private static void handleLoad(ServerPlayer player, AbstractContainerMenu menu,
                                   Slot slot, ItemStack mag, MagazineItem magItem) {
        ItemStack cursor = menu.getCarried();
        if (cursor.isEmpty() || !(cursor.getItem() instanceof IAmmo iAmmo)) return;

        ResourceLocation heldAmmoId = iAmmo.getAmmoId(cursor);
        if (DefaultAssets.EMPTY_AMMO_ID.equals(heldAmmoId)) return;

        // Ammo type must match the magazine family
        String familyId = MagazineItem.getMagazineFamilyId(mag);
        if (familyId == null) return;
        ResourceLocation familyAmmo = MagazineFamilySystem.getAmmoTypeForFamily(familyId);
        if (familyAmmo == null || !familyAmmo.equals(heldAmmoId)) return;

        // Magazine ammo type must be empty or the same kind
        ResourceLocation magAmmoId = magItem.getAmmoId(mag);
        if (!DefaultAssets.EMPTY_AMMO_ID.equals(magAmmoId) && !heldAmmoId.equals(magAmmoId)) return;

        int maxCap  = MagazineItem.getMaxCapacity(mag);
        int current = magItem.getAmmoCount(mag);
        if (current >= maxCap) return;

        magItem.setAmmoId(mag, heldAmmoId);
        magItem.setAmmoCount(mag, current + 1);
        cursor.shrink(1);

        slot.set(mag);
        menu.setCarried(cursor);
    }

    // ── Unload one bullet: magazine → cursor/inventory ───────────────────────

    private static void handleUnload(ServerPlayer player, AbstractContainerMenu menu,
                                     Slot slot, ItemStack mag, MagazineItem magItem) {
        int currentAmmo = magItem.getAmmoCount(mag);
        if (currentAmmo <= 0) return;

        ResourceLocation ammoId = magItem.getAmmoId(mag);
        if (DefaultAssets.EMPTY_AMMO_ID.equals(ammoId)) return;

        // Decrement mag
        int newCount = currentAmmo - 1;
        magItem.setAmmoCount(mag, newCount);
        if (newCount == 0) magItem.setAmmoId(mag, DefaultAssets.EMPTY_AMMO_ID);
        slot.set(mag);

        // Try to put the bullet on the cursor first
        ItemStack cursor = menu.getCarried();
        ItemStack bullet = AmmoItemBuilder.create().setId(ammoId).setCount(1).build();

        if (cursor.isEmpty()) {
            menu.setCarried(bullet);
        } else if (cursor.getItem() == bullet.getItem()
                && ItemStack.isSameItemSameTags(cursor, bullet)
                && cursor.getCount() < cursor.getMaxStackSize()) {
            cursor.grow(1);
            menu.setCarried(cursor);
        } else {
            // Cursor is occupied by something else — stash in inventory
            if (!player.getInventory().add(bullet)) {
                player.drop(bullet, false);
            }
        }
    }
}
