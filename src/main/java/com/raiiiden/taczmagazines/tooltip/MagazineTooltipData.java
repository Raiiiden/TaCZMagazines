package com.raiiiden.taczmagazines.tooltip;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

public class MagazineTooltipData implements TooltipComponent {

    private final ItemStack magazineStack;

    public MagazineTooltipData(ItemStack magazineStack) {
        this.magazineStack = magazineStack;
    }

    public ItemStack getMagazineStack() {
        return magazineStack;
    }
}