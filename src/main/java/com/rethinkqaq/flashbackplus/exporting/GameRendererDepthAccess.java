package com.rethinkqaq.flashbackplus.exporting;

import com.mojang.blaze3d.pipeline.RenderTarget;

/** Access bridge implemented by the GameRenderer mixin for export cleanup. */
public interface GameRendererDepthAccess {
    void flashbackplus_captureDepthForFrame(RenderTarget target, long frameId);
    void flashbackplus_flushDepthPbo();
}
