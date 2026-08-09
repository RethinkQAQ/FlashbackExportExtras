package com.rethinkqaq.flashbackplus.gpu;

/** Owns the render-thread backend used by the current export. */
public final class GpuExportBackendFactory {
    private static GpuExportBackend backend;

    private GpuExportBackendFactory() {}

    public static synchronized GpuExportBackend get() {
        if (backend == null) backend = create();
        return backend;
    }

    public static synchronized void reset() {
        if (backend != null) backend.close();
        backend = null;
    }

    private static GpuExportBackend create() {
        return new LegacyOpenGlExportBackend();
    }
}
