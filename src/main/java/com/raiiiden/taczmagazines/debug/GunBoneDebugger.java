package com.raiiiden.taczmagazines.debug;

import com.raiiiden.taczmagazines.TaCZMagazines;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Optional;

@OnlyIn(Dist.CLIENT)
public class GunBoneDebugger {

    /**
     * Dumps the bone hierarchy of the gun currently held in the main hand to the log.
     * Triggered by {@code /magazine bonedebug} (client-side command).
     *
     * @return a short status string suitable for feedback in chat
     */
    public static String run() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return "No player found.";

        ItemStack heldItem = mc.player.getMainHandItem();
        if (heldItem.isEmpty() || !(heldItem.getItem() instanceof IGun iGun)) {
            return "Hold a gun to inspect its bones.";
        }

        Optional<GunDisplayInstance> displayOpt = TimelessAPI.getGunDisplay(heldItem);
        if (displayOpt.isEmpty()) return "No display instance found for this gun.";

        BedrockGunModel gunModel = displayOpt.get().getGunModel();
        if (gunModel == null || gunModel.getRootNode() == null) return "Gun model or root node is null.";

        TaCZMagazines.LOGGER.info("=== Gun Bone Structure for: {} ===", iGun.getGunId(heldItem));
        logBoneHierarchy(gunModel.getRootNode(), 0);
        TaCZMagazines.LOGGER.info("=== End of Bone Structure ===");

        return "Bone structure logged for " + iGun.getGunId(heldItem) + " — check your log.";
    }

    private static void logBoneHierarchy(BedrockPart part, int depth) {
        if (part == null) return;
        String indent = "  ".repeat(depth);
        String visibility = part.visible ? "visible" : "HIDDEN";
        TaCZMagazines.LOGGER.info("{}|- {} ({})", indent, part.name, visibility);
        for (BedrockPart child : part.children) {
            logBoneHierarchy(child, depth + 1);
        }
    }
}
