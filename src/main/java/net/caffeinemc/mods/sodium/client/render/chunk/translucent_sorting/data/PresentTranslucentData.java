package net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.data;

import net.caffeinemc.mods.sodium.client.gl.util.VertexRange;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TQuad;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import net.minecraft.core.SectionPos;

/**
 * Super class for translucent data that contains an actual buffer.
 */
public abstract class PresentTranslucentData extends TranslucentData {
    private NativeBuffer buffer;
    private boolean reuseUploadedData;
    private int quadHash;
    private int length;

    PresentTranslucentData(SectionPos sectionPos, NativeBuffer buffer) {
        super(sectionPos);
        this.buffer = buffer;
        this.length = TranslucentData.indexBytesToQuadCount(buffer.getLength());
    }

    public abstract VertexRange[] getVertexRanges();

    @Override
    public void delete() {
        super.delete();
        if (this.buffer != null) {
            this.buffer.free();
            this.buffer = null;
        }
    }

    public void setQuadHash(int hash) {
        this.quadHash = hash;
    }

    public int getQuadHash() {
        return this.quadHash;
    }

    public int getLength() {
        return this.length;
    }

    public NativeBuffer getBuffer() {
        return this.buffer;
    }

    public boolean isReusingUploadedData() {
        return this.reuseUploadedData;
    }

    public void setReuseUploadedData() {
        this.reuseUploadedData = true;
    }

    public void unsetReuseUploadedData() {
        this.reuseUploadedData = false;
    }

    public static NativeBuffer nativeBufferForQuads(TQuad[] quads) {
        return new NativeBuffer(TranslucentData.quadCountToIndexBytes(quads.length));
    }

    public static NativeBuffer nativeBufferForQuads(NativeBuffer existing, TQuad[] quads) {
        if (existing != null) {
            return existing;
        }
        return nativeBufferForQuads(quads);
    }
}
