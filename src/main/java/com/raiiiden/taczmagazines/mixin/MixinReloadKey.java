package com.raiiiden.taczmagazines.mixin;

import com.raiiiden.taczmagazines.client.ClientReloadKeyHandler;
import com.tacz.guns.client.input.ReloadKey;
import com.tacz.guns.util.InputExtraCheck;
import net.minecraftforge.client.event.InputEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ReloadKey.class, remap = false)
public abstract class MixinReloadKey {

    @Inject(method = "onReloadPress", at = @At("HEAD"), cancellable = true)
    private static void interceptReloadPress(InputEvent.Key event, CallbackInfo ci) {
        if (!InputExtraCheck.isInGame()) return;
        if (event.getAction() != 1 /* GLFW_PRESS */) return;
        if (!ReloadKey.RELOAD_KEY.matches(event.getKey(), event.getScanCode())) return;

        // Ask our handler how to process this press.
        // Returns true → we handle it (cancel TaCZ now, reload on release).
        // Returns false → let TaCZ fire its normal reload.
        if (ClientReloadKeyHandler.onReloadKeyPressed()) {
            ci.cancel();
        }
    }

    @Inject(method = "onReloadPress", at = @At("HEAD"), cancellable = true)
    private static void interceptReloadRelease(InputEvent.Key event, CallbackInfo ci) {
        if (!InputExtraCheck.isInGame()) return;
        if (event.getAction() != 0 /* GLFW_RELEASE */) return;
        if (!ReloadKey.RELOAD_KEY.matches(event.getKey(), event.getScanCode())) return;

        ClientReloadKeyHandler.onReloadKeyReleased();
        // Do NOT cancel here — TaCZ ignores RELEASE actions anyway (it checks action == 1).
    }
}
