package com.rethinkqaq.flashbackexportextras.gpu;

import java.util.ArrayDeque;
import java.util.Deque;

/** Owns the render-thread backend used by the current export. */
public final class GpuExportBackendFactory {
    private static GpuExportBackend backend;
    private static final Deque<GpuExportBackend> pendingRelease = new ArrayDeque<>();

    private GpuExportBackendFactory() {}

    public static synchronized GpuExportBackend get() {
        if (backend == null) backend = create();
        return backend;
    }

    public static synchronized void reset() {
        if (backend != null) pendingRelease.addLast(backend);
        backend = null;
    }

    /** Called only from a render-thread safe point. */
    public static synchronized void releasePendingOnRenderThread() {
        while (!pendingRelease.isEmpty()) {
            GpuExportBackend candidate = pendingRelease.peekFirst();
            if (!candidate.releaseOnRenderThread()) return;
            pendingRelease.removeFirst();
        }
    }

    private static GpuExportBackend create() {
        /*? if >=26.1 {*/
        /*return new Blaze3dExportBackend();
        *//*?} else {*/
        return new LegacyOpenGlExportBackend();
        /*?}*/
    }
}
