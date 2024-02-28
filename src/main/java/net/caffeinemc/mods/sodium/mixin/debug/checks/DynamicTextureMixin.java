package net.caffeinemc.mods.sodium.mixin.debug.checks;

import net.caffeinemc.mods.sodium.client.render.util.RenderAsserts;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(DynamicTexture.class)
public class DynamicTextureMixin {
    @Redirect(method = "<init>(Lcom/mojang/blaze3d/platform/NativeImage;)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;isOnRenderThread()Z"))
    private boolean validateCurrentThread$init() {
        return RenderAsserts.validateCurrentThread();
    }
}
