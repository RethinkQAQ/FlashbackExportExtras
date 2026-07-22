package com.rethinkqaq.flashbackplus.exporting;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL30;

import java.io.IOException;

/**
 * HDR color transform: scRGB-nl (RGBA16F) → BT.2020 + PQ (RGBA16).
 */
public class HdrColorTransformShader implements AutoCloseable {

    private int dstTextureId = -1;
    private int dstFboId = -1;
    private int width = -1;
    private int height = -1;
    private ShaderInstance shader;
    private boolean initialized;

    public HdrColorTransformShader() {
    }

    private void ensureResources(int w, int h) {
        if (initialized && this.width == w && this.height == h) return;

        close();

        this.width = w;
        this.height = h;

        // Create destination texture: GL_RGBA16 (16-bit UNORM) for PQ output
        this.dstTextureId = GlStateManager._genTexture();
        GlStateManager._bindTexture(dstTextureId);
        GlStateManager._texParameter(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_NEAREST);
        GlStateManager._texParameter(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAG_FILTER, GL30.GL_NEAREST);
        GlStateManager._texParameter(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_S, GL30.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_T, GL30.GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(GL30.GL_TEXTURE_2D, 0, GL30.GL_RGBA16, w, h,
                0, GL30.GL_RGBA, GL30.GL_UNSIGNED_SHORT, null);

        // Create FBO
        this.dstFboId = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, dstFboId);
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL30.GL_TEXTURE_2D, dstTextureId, 0);
        int status = GlStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("HDR FBO incomplete: 0x" + Integer.toHexString(status));
        }
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GlStateManager._bindTexture(0);

        // Load shader
        try {
            this.shader = new ShaderInstance(
                    Minecraft.getInstance().getResourceManager(),
                    "flashbackplus_hdr_color_transform", DefaultVertexFormat.POSITION_TEX);
            // Verify uniforms exist (fail-fast on misconfiguration)
            if (this.shader.safeGetUniform("UiBrightness") == null) {
                throw new RuntimeException("HDR shader missing UiBrightness uniform");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load HDR color transform shader", e);
        }

        this.initialized = true;
    }

    /**
     * Runs the fullscreen color transform pass.
     *
     * @param srcTextureId   Source GL texture ID (RGBA16F, scRGB-nl)
     * @param peakBrightness Display peak brightness in nits
     * @return Destination GL texture ID (RGBA16, PQ-encoded)
     */
    public int render(int srcTextureId, float peakBrightness) {
        RenderSystem.assertOnRenderThread();

        // Read source texture dimensions (must match what the game actually rendered)
        GlStateManager._bindTexture(srcTextureId);
        int texW = GL30.glGetTexLevelParameteri(GL30.GL_TEXTURE_2D, 0, GL30.GL_TEXTURE_WIDTH);
        int texH = GL30.glGetTexLevelParameteri(GL30.GL_TEXTURE_2D, 0, GL30.GL_TEXTURE_HEIGHT);
        GlStateManager._bindTexture(0);

        if (texW <= 0 || texH <= 0) {
            throw new IllegalStateException("Source texture has invalid size: " + texW + "x" + texH);
        }
        ensureResources(texW, texH);

        // Save current GL state
        int[] prevViewport = new int[4];
        GL30.glGetIntegerv(GL30.GL_VIEWPORT, prevViewport);
        int prevDrawFbo = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevReadFbo = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        // Bind our FBO for drawing only (don't touch read FBO)
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, dstFboId);
        GlStateManager._viewport(0, 0, width, height);

        // Ensure correct render state
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._disableBlend();
        GlStateManager._colorMask(true, true, true, true);

        // Apply shader with texture + uniforms
        shader.setSampler("InSampler", srcTextureId);
        shader.safeGetUniform("UiBrightness").set(peakBrightness);
        shader.safeGetUniform("Primaries").set(6);
        shader.safeGetUniform("TransferFunction").set(11);
        shader.apply();

        // Draw fullscreen quad
        Tesselator tesselator = RenderSystem.renderThreadTesselator();
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buffer.addVertex(0.0F, 0.0F, 0.0F).setUv(0.0F, 0.0F);
        buffer.addVertex(1.0F, 0.0F, 0.0F).setUv(1.0F, 0.0F);
        buffer.addVertex(1.0F, 1.0F, 0.0F).setUv(1.0F, 1.0F);
        buffer.addVertex(0.0F, 1.0F, 0.0F).setUv(0.0F, 1.0F);
        BufferUploader.draw(buffer.buildOrThrow());

        shader.clear();

        // Restore previous state
        GlStateManager._depthMask(true);
        GlStateManager._enableDepthTest();
        GlStateManager._viewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);

        return dstTextureId;
    }

    @Override
    public void close() {
        if (this.shader != null) {
            this.shader.close();
            this.shader = null;
        }
        if (this.dstFboId >= 0) {
            GlStateManager._glDeleteFramebuffers(this.dstFboId);
            this.dstFboId = -1;
        }
        if (this.dstTextureId >= 0) {
            GlStateManager._deleteTexture(this.dstTextureId);
            this.dstTextureId = -1;
        }
        this.initialized = false;
    }
}
