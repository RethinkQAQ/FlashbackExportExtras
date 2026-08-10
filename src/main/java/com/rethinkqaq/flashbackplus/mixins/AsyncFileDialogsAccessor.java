package com.rethinkqaq.flashbackplus.mixins;

import com.moulberry.flashback.exporting.AsyncFileDialogs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.ExecutorService;

/** Gives the client shutdown hook access to Flashback's file-dialog executor. */
@Mixin(value = AsyncFileDialogs.class, remap = false)
public interface AsyncFileDialogsAccessor {
    @Accessor("dialogThread")
    static ExecutorService flashbackplus$getDialogThread() {
        throw new AssertionError();
    }
}
