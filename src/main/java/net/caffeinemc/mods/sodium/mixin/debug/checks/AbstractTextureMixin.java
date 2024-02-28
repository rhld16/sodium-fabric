package net.caffeinemc.mods.sodium.mixin.debug.checks;

import net.caffeinemc.mods.sodium.client.render.util.RenderAsserts;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(AbstractTexture.class)
public class AbstractTextureMixin {
    @Redirect(method = {
            "releaseId"
    }, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;isOnRenderThread()Z"))
    private boolean validateCurrentThread$cleanup() {
        return RenderAsserts.validateCurrentThread();
    }

    @Redirect(method = {
            "bind"
    }, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;isOnRenderThreadOrInit()Z"))
    private boolean validateCurrentThread$bind() {
        return RenderAsserts.validateCurrentThread();
    }
}
