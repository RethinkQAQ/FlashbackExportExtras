package com.rethinkqaq.flashbackexportextras.mixins;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;

public final class FlashbackPlusMixinConfigPlugin implements IMixinConfigPlugin {
    private static final String DUMMY_TARGET = "com.rethinkqaq.flashbackexportextras.utils.Dummy";
    private boolean hdrLoaded;
    private boolean irisPipelineMixinEnabled;
    @Override public void onLoad(String mixinPackage) {
        hdrLoaded = FabricLoader.getInstance().isModLoaded("hdr_mod");
        irisPipelineMixinEnabled = FabricLoader.getInstance().isModLoaded("iris");
    }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        if (mixin.endsWith("MixinHDRModConfig")) return hdrLoaded;
        if (mixin.endsWith("MixinHdrModColorTransformRenderer")) {
            return hdrLoaded && isRealTarget(target);
        }
        if (mixin.endsWith("MixinIrisRenderingPipeline")) {
            return irisPipelineMixinEnabled && isRealTarget(target);
        }
        if (mixin.endsWith("MixinGlCommandEncoderDepthCopy")
                || mixin.endsWith("DirectStateAccessInvoker")) {
            return isRealTarget(target);
        }
        return true;
    }
    private static boolean isRealTarget(String target) { return !DUMMY_TARGET.equals(target); }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
