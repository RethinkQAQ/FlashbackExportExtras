package com.rethinkqaq.flashbackplus.mixins;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;

public final class FlashbackPlusMixinConfigPlugin implements IMixinConfigPlugin {
    private boolean hdrLoaded;
    @Override public void onLoad(String mixinPackage) { hdrLoaded = FabricLoader.getInstance().isModLoaded("hdr_mod"); }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        return !mixin.endsWith("MixinHDRModConfig") || hdrLoaded;
    }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
