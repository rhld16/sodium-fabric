package net.caffeinemc.mods.sodium.client.render.immediate;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.api.vertex.format.common.ColorVertex;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.caffeinemc.mods.sodium.client.util.MathUtil;
import net.caffeinemc.mods.sodium.api.util.ColorABGR;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.api.util.ColorMixer;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;

public class CloudRenderer {
    private static final ResourceLocation CLOUDS_TEXTURE_ID = new ResourceLocation("textures/environment/clouds.png");

    private static final int CLOUD_COLOR_NEG_Y = ColorABGR.pack(0.7F, 0.7F, 0.7F, 1.0f);
    private static final int CLOUD_COLOR_POS_Y = ColorABGR.pack(1.0f, 1.0f, 1.0f, 1.0f);
    private static final int CLOUD_COLOR_NEG_X = ColorABGR.pack(0.9F, 0.9F, 0.9F, 1.0f);
    private static final int CLOUD_COLOR_POS_X = ColorABGR.pack(0.9F, 0.9F, 0.9F, 1.0f);
    private static final int CLOUD_COLOR_NEG_Z = ColorABGR.pack(0.8F, 0.8F, 0.8F, 1.0f);
    private static final int CLOUD_COLOR_POS_Z = ColorABGR.pack(0.8F, 0.8F, 0.8F, 1.0f);

    private static final int DIR_NEG_Y = 1 << 0;
    private static final int DIR_POS_Y = 1 << 1;
    private static final int DIR_NEG_X = 1 << 2;
    private static final int DIR_POS_X = 1 << 3;
    private static final int DIR_NEG_Z = 1 << 4;
    private static final int DIR_POS_Z = 1 << 5;

    private VertexBuffer vertexBuffer;
    private CloudEdges edges;
    private ShaderInstance shader;
    private final FogRenderer.FogData fogData = new FogRenderer.FogData(FogRenderer.FogMode.FOG_TERRAIN);

    private int prevCenterCellX, prevCenterCellY, cachedRenderDistance;
    private CloudStatus cloudRenderMode;

    public CloudRenderer(ResourceProvider resourceProvider) {
        this.reloadTextures(resourceProvider);
    }

    public void render(@Nullable ClientLevel level, LocalPlayer player, PoseStack matrices, Matrix4f projectionMatrix, float ticks, float tickDelta, double cameraX, double cameraY, double cameraZ) {
        if (level == null) {
            return;
        }

        float cloudHeight = level.effects().getCloudHeight();

        // Vanilla uses NaN height as a way to disable cloud rendering
        if (Float.isNaN(cloudHeight)) {
            return;
        }

        Vec3 color = level.getCloudColor(tickDelta);

        double cloudTime = (ticks + tickDelta) * 0.03F;
        double cloudCenterX = (cameraX + cloudTime);
        double cloudCenterZ = (cameraZ) + 0.33D;

        int renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance();
        int cloudDistance = Math.max(32, (renderDistance * 2) + 9);

        int centerCellX = (int) (Math.floor(cloudCenterX / 12));
        int centerCellZ = (int) (Math.floor(cloudCenterZ / 12));

        if (this.vertexBuffer == null || this.prevCenterCellX != centerCellX || this.prevCenterCellY != centerCellZ || this.cachedRenderDistance != renderDistance || cloudRenderMode != Minecraft.getInstance().options.getCloudsType()) {
            BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
            bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

            this.cloudRenderMode = Minecraft.getInstance().options.getCloudsType();

            this.rebuildGeometry(bufferBuilder, cloudDistance, centerCellX, centerCellZ);

            if (this.vertexBuffer == null) {
                this.vertexBuffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            }

            this.vertexBuffer.bind();
            this.vertexBuffer.upload(bufferBuilder.end());

            VertexBuffer.unbind();

            this.prevCenterCellX = centerCellX;
            this.prevCenterCellY = centerCellZ;
            this.cachedRenderDistance = renderDistance;
        }

        float previousEnd = RenderSystem.getShaderFogEnd();
        float previousStart = RenderSystem.getShaderFogStart();
        this.fogData.end = cloudDistance * 8;
        this.fogData.start = (cloudDistance * 8) - 16;

        applyFogModifiers(level, this.fogData, player, cloudDistance * 8, tickDelta);


        RenderSystem.setShaderFogEnd(this.fogData.end);
        RenderSystem.setShaderFogStart(this.fogData.start);

        float translateX = (float) (cloudCenterX - (centerCellX * 12));
        float translateZ = (float) (cloudCenterZ - (centerCellZ * 12));

        RenderSystem.enableDepthTest();

        this.vertexBuffer.bind();

        boolean insideClouds = cameraY < cloudHeight + 4.5f && cameraY > cloudHeight - 0.5f;
        boolean fastClouds = cloudRenderMode == CloudStatus.FAST;

        if (insideClouds || fastClouds) {
            RenderSystem.disableCull();
        } else {
            RenderSystem.enableCull();
        }

        RenderSystem.setShaderColor((float) color.x, (float) color.y, (float) color.z, 0.8f);

        matrices.pushPose();

        Matrix4f modelViewMatrix = matrices.last().pose();
        modelViewMatrix.translate(-translateX, cloudHeight - (float) cameraY + 0.33F, -translateZ);

        // PASS 1: Set up depth buffer
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.colorMask(false, false, false, false);

        this.vertexBuffer.drawWithShader(modelViewMatrix, projectionMatrix, this.shader);

        // PASS 2: Render geometry
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL30C.GL_EQUAL);
        RenderSystem.colorMask(true, true, true, true);

        this.vertexBuffer.drawWithShader(modelViewMatrix, projectionMatrix, this.shader);

        matrices.popPose();

        VertexBuffer.unbind();

        RenderSystem.disableBlend();
        RenderSystem.depthFunc(GL30C.GL_LEQUAL);

        RenderSystem.enableCull();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        RenderSystem.setShaderFogEnd(previousEnd);
        RenderSystem.setShaderFogStart(previousStart);
    }

    private void applyFogModifiers(ClientLevel level, FogRenderer.FogData fogData, LocalPlayer player, int cloudDistance, float tickDelta) {
        if (Minecraft.getInstance().gameRenderer == null || Minecraft.getInstance().gameRenderer.getMainCamera() == null) {
            return;
        }

        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        FogType cameraSubmersionType = camera.getFluidInCamera();
        if (cameraSubmersionType == FogType.LAVA) {
            if (player.isSpectator()) {
                fogData.start = -8.0f;
                fogData.end = (cloudDistance) * 0.5f;
            } else if (player.hasEffect(MobEffects.FIRE_RESISTANCE)) {
                fogData.start = 0.0f;
                fogData.end = 3.0f;
            } else {
                fogData.start = 0.25f;
                fogData.end = 1.0f;
            }
        } else if (cameraSubmersionType == FogType.POWDER_SNOW) {
            if (player.isSpectator()) {
                fogData.start = -8.0f;
                fogData.end = (cloudDistance) * 0.5f;
            } else {
                fogData.start = 0.0f;
                fogData.end = 2.0f;
            }
        } else if (cameraSubmersionType == FogType.WATER) {
            fogData.start = -8.0f;
            fogData.end = 96.0f;
            fogData.end *= Math.max(0.25f, player.getWaterVision());
            if (fogData.end > (cloudDistance)) {
                fogData.end = cloudDistance;
                fogData.shape = FogShape.CYLINDER;
            }
        } else if (level.effects().isFoggyAt(Mth.floor(camera.getPosition().x), Mth.floor(camera.getPosition().z)) || Minecraft.getInstance().gui.getBossOverlay().shouldCreateWorldFog()) {
            fogData.start = (cloudDistance) * 0.05f;
            fogData.end = Math.min((cloudDistance), 192.0f) * 0.5f;
        }

        FogRenderer.MobEffectFogFunction fogModifier = FogRenderer.getPriorityFogFunction(player, tickDelta);
        if (fogModifier != null) {
            MobEffectInstance statusEffectInstance = player.getEffect(fogModifier.getMobEffect());
            if (statusEffectInstance != null) {
                fogModifier.setupFog(fogData, player, statusEffectInstance, (cloudDistance * 8), tickDelta);
            }
        }
    }

    private void rebuildGeometry(BufferBuilder bufferBuilder, int cloudDistance, int centerCellX, int centerCellZ) {
        var writer = VertexBufferWriter.of(bufferBuilder);
        boolean fastClouds = cloudRenderMode == CloudStatus.FAST;

        for (int offsetX = -cloudDistance; offsetX < cloudDistance; offsetX++) {
            for (int offsetZ = -cloudDistance; offsetZ < cloudDistance; offsetZ++) {
                int connectedEdges = this.edges.getEdges(centerCellX + offsetX, centerCellZ + offsetZ);

                if (connectedEdges == 0) {
                    continue;
                }

                int texel = this.edges.getColor(centerCellX + offsetX, centerCellZ + offsetZ);

                float x = offsetX * 12;
                float z = offsetZ * 12;

                try (MemoryStack stack = MemoryStack.stackPush()) {
                    final long buffer = stack.nmalloc((fastClouds ? 4 : (6 * 4)) * ColorVertex.STRIDE);

                    long ptr = buffer;
                    int count = 0;

                    // -Y
                    if ((connectedEdges & DIR_NEG_Y) != 0) {
                        int mixedColor = ColorMixer.mul(texel, fastClouds ? CLOUD_COLOR_POS_Y : CLOUD_COLOR_NEG_Y);

                        ptr = writeVertex(ptr, x + 12, 0.0f, z + 12, mixedColor);
                        ptr = writeVertex(ptr, x + 0.0f, 0.0f, z + 12, mixedColor);
                        ptr = writeVertex(ptr, x + 0.0f, 0.0f, z + 0.0f, mixedColor);
                        ptr = writeVertex(ptr, x + 12, 0.0f, z + 0.0f, mixedColor);

                        count += 4;
                    }

                    // Only emit -Y geometry to emulate vanilla fast clouds
                    if (fastClouds) {
                        writer.push(stack, buffer, count, ColorVertex.FORMAT);
                        continue;
                    }

                    // +Y
                    if ((connectedEdges & DIR_POS_Y) != 0) {
                        int mixedColor = ColorMixer.mul(texel, CLOUD_COLOR_POS_Y);

                        ptr = writeVertex(ptr, x + 0.0f, 4.0f, z + 12, mixedColor);
                        ptr = writeVertex(ptr, x + 12, 4.0f, z + 12, mixedColor);
                        ptr = writeVertex(ptr, x + 12, 4.0f, z + 0.0f, mixedColor);
                        ptr = writeVertex(ptr, x + 0.0f, 4.0f, z + 0.0f, mixedColor);

                        count += 4;
                    }

                    // -X
                    if ((connectedEdges & DIR_NEG_X) != 0) {
                        int mixedColor = ColorMixer.mul(texel, CLOUD_COLOR_NEG_X);

                        ptr = writeVertex(ptr, x + 0.0f, 0.0f, z + 12, mixedColor);
                        ptr = writeVertex(ptr, x + 0.0f, 4.0f, z + 12, mixedColor);
                        ptr = writeVertex(ptr, x + 0.0f, 4.0f, z + 0.0f, mixedColor);
                        ptr = writeVertex(ptr, x + 0.0f, 0.0f, z + 0.0f, mixedColor);

                        count += 4;
                    }

                    // +X
                    if ((connectedEdges & DIR_POS_X) != 0) {
                        int mixedColor = ColorMixer.mul(texel, CLOUD_COLOR_POS_X);

                        ptr = writeVertex(ptr, x + 12, 4.0f, z + 12, mixedColor);
                        ptr = writeVertex(ptr, x + 12, 0.0f, z + 12, mixedColor);
                        ptr = writeVertex(ptr, x + 12, 0.0f, z + 0.0f, mixedColor);
                        ptr = writeVertex(ptr, x + 12, 4.0f, z + 0.0f, mixedColor);

                        count += 4;
                    }

                    // -Z
                    if ((connectedEdges & DIR_NEG_Z) != 0) {
                        int mixedColor = ColorMixer.mul(texel, CLOUD_COLOR_NEG_Z);

                        ptr = writeVertex(ptr, x + 12, 4.0f, z + 0.0f, mixedColor);
                        ptr = writeVertex(ptr, x + 12, 0.0f, z + 0.0f, mixedColor);
                        ptr = writeVertex(ptr, x + 0.0f, 0.0f, z + 0.0f, mixedColor);
                        ptr = writeVertex(ptr, x + 0.0f, 4.0f, z + 0.0f, mixedColor);

                        count += 4;
                    }

                    // +Z
                    if ((connectedEdges & DIR_POS_Z) != 0) {
                        int mixedColor = ColorMixer.mul(texel, CLOUD_COLOR_POS_Z);

                        ptr = writeVertex(ptr, x + 12, 0.0f, z + 12, mixedColor);
                        ptr = writeVertex(ptr, x + 12, 4.0f, z + 12, mixedColor);
                        ptr = writeVertex(ptr, x + 0.0f, 4.0f, z + 12, mixedColor);
                        ptr = writeVertex(ptr, x + 0.0f, 0.0f, z + 12, mixedColor);

                        count += 4;
                    }

                    if (count > 0) {
                        writer.push(stack, buffer, count, ColorVertex.FORMAT);
                    }
                }
            }
        }
    }

    private static long writeVertex(long buffer, float x, float y, float z, int color) {
        ColorVertex.put(buffer, x, y, z, color);
        return buffer + ColorVertex.STRIDE;
    }

    public void reloadTextures(ResourceProvider resourceProvider) {
        this.destroy();

        this.edges = createCloudEdges();

        try {
            this.shader = new ShaderInstance(resourceProvider, "clouds", DefaultVertexFormat.POSITION_COLOR);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void destroy() {
        if (this.shader != null) {
            this.shader.close();
            this.shader = null;
        }

        if (this.vertexBuffer != null) {
            this.vertexBuffer.close();
            this.vertexBuffer = null;
        }
    }

    private static CloudEdges createCloudEdges() {
        ResourceManager resourceManager = Minecraft.getInstance().getResourceManager();
        Resource resource = resourceManager.getResource(CLOUDS_TEXTURE_ID)
                .orElseThrow();

        try (InputStream inputStream = resource.open()){
            try (NativeImage nativeImage = NativeImage.read(inputStream)) {
                return new CloudEdges(nativeImage);
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to load texture data", ex);
        }
    }

    private static class CloudEdges {
        private final byte[] edges;
        private final int[] colors;
        private final int width, height;

        public CloudEdges(NativeImage texture) {
            int width = texture.getWidth();
            int height = texture.getHeight();

            Validate.isTrue(MathUtil.isPowerOfTwo(width), "Texture width must be power-of-two");
            Validate.isTrue(MathUtil.isPowerOfTwo(height), "Texture height must be power-of-two");

            this.edges = new byte[width * height];
            this.colors = new int[width * height];

            this.width = width;
            this.height = height;

            for (int x = 0; x < width; x++) {
                for (int z = 0; z < height; z++) {
                    int index = index(x, z, width, height);
                    int cell = texture.getPixelRGBA(x, z);

                    this.colors[index] = cell;

                    int edges = 0;

                    if (isOpaqueCell(cell)) {
                        edges |= DIR_NEG_Y | DIR_POS_Y;

                        int negX = texture.getPixelRGBA(wrap(x - 1, width), wrap(z, height));

                        if (cell != negX) {
                            edges |= DIR_NEG_X;
                        }

                        int posX = texture.getPixelRGBA(wrap(x + 1, width), wrap(z, height));

                        if (!isOpaqueCell(posX) && cell != posX) {
                            edges |= DIR_POS_X;
                        }

                        int negZ = texture.getPixelRGBA(wrap(x, width), wrap(z - 1, height));

                        if (cell != negZ) {
                            edges |= DIR_NEG_Z;
                        }

                        int posZ = texture.getPixelRGBA(wrap(x, width), wrap(z + 1, height));

                        if (!isOpaqueCell(posZ) && cell != posZ) {
                            edges |= DIR_POS_Z;
                        }
                    }

                    this.edges[index] = (byte) edges;
                }
            }
        }

        private static boolean isOpaqueCell(int color) {
            return ColorARGB.unpackAlpha(color) > 1;
        }

        public int getEdges(int x, int z) {
            return this.edges[index(x, z, this.width, this.height)];
        }

        public int getColor(int x, int z) {
            return this.colors[index(x, z, this.width, this.height)];
        }

        private static int index(int posX, int posZ, int width, int height) {
            return (wrap(posX, width) * width) + wrap(posZ, height);
        }

        private static int wrap(int pos, int dim) {
            return (pos & (dim - 1));
        }
    }
}
