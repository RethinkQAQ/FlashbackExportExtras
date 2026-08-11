package com.rethinkqaq.flashbackplus.mixins;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;

public final class FlashbackPlusMixinConfigPlugin implements IMixinConfigPlugin {
    private boolean hdrLoaded;
    private boolean blazeDepthCopyMixinEnabled;
    private boolean irisPipelineMixinEnabled;
    @Override public void onLoad(String mixinPackage) {
        hdrLoaded = FabricLoader.getInstance().isModLoaded("hdr_mod");
        String minecraftVersion = FabricLoader.getInstance().getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("");
        blazeDepthCopyMixinEnabled = minecraftVersion.startsWith("26.2");
        irisPipelineMixinEnabled = blazeDepthCopyMixinEnabled
                && FabricLoader.getInstance().isModLoaded("iris");
    }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        if (mixin.endsWith("MixinHDRModConfig")) return hdrLoaded;
        if (mixin.endsWith("MixinGlCommandEncoderDepthCopy")) return blazeDepthCopyMixinEnabled;
        if (mixin.endsWith("MixinIrisRenderingPipeline")) return irisPipelineMixinEnabled;
        return true;
    }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
