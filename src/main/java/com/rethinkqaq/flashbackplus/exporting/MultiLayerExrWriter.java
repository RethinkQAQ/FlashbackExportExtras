package com.rethinkqaq.flashbackplus.exporting;

import com.mojang.blaze3d.platform.NativeImage;
import com.rethinkqaq.flashbackplus.Flashbackplus;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyexr.EXRChannelInfo;
import org.lwjgl.util.tinyexr.EXRHeader;
import org.lwjgl.util.tinyexr.EXRImage;
import org.lwjgl.util.tinyexr.TinyEXR;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-layer OpenEXR writer using LWJGL tinyexr bindings.
 * Same approach as ReplayMod — SaveEXRImageToFile with EXRHeader/EXRImage.
 */
public class MultiLayerExrWriter implements AutoCloseable {

    private static final int NUM_CHANNELS = 5;

    // ReplayMod-proven order: A, B, G, R (matches BGRA pixel data layout)
    private static final String[] CHANNEL_NAMES = {
        "View Layer.Combined.A",
        "View Layer.Combined.B",
        "View Layer.Combined.G",
        "View Layer.Combined.R",
        "View Layer.Depth.Z"
    };

    private final Path outputDir;
    private final int width;
    private final int height;
    private final boolean linearizeDepth;
    private int frameCount;

    public MultiLayerExrWriter(Path outputDir, int width, int height, boolean linearizeDepth) throws IOException {
        this.outputDir = outputDir;
        this.width = width;
        this.height = height;
        this.linearizeDepth = linearizeDepth;
        this.frameCount = 0;
        Files.createDirectories(outputDir);
    }

    public void writeFrame(NativeImage colorImage, FloatBuffer depthBuffer, int frameNumber) throws IOException {
        int pixelCount = width * height;

        FloatBuffer rBuf = MemoryUtil.memAllocFloat(pixelCount);
        FloatBuffer gBuf = MemoryUtil.memAllocFloat(pixelCount);
        FloatBuffer bBuf = MemoryUtil.memAllocFloat(pixelCount);
        FloatBuffer aBuf = MemoryUtil.memAllocFloat(pixelCount);
        FloatBuffer zBuf = MemoryUtil.memAllocFloat(pixelCount);

        try {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int idx = y * width + x;
                    int pixel = colorImage.getPixelRGBA(x, y);
                    rBuf.put(idx, (pixel & 0xFF) / 255.0f);
                    gBuf.put(idx, ((pixel >> 8) & 0xFF) / 255.0f);
                    bBuf.put(idx, ((pixel >> 16) & 0xFF) / 255.0f);
                    aBuf.put(idx, ((pixel >> 24) & 0xFF) / 255.0f);
                    // Depth is bottom-up from GL; flip Y to match flipped color
                    float depth = depthBuffer.get((height - 1 - y) * width + x);
                    if (linearizeDepth) {
                        // OpenGL NDC→world-space linearization
                        float znear = 0.05f;
                        float zfar = DepthCaptureState.depthFar;
                        depth = 2.0f * znear * zfar / (zfar + znear - (2.0f * depth - 1.0f) * (zfar - znear));
                    }
                    zBuf.put(idx, depth);
                }
            }

            writeExr(rBuf, gBuf, bBuf, aBuf, zBuf, frameNumber);
        } finally {
            MemoryUtil.memFree(rBuf);
            MemoryUtil.memFree(gBuf);
            MemoryUtil.memFree(bBuf);
            MemoryUtil.memFree(aBuf);
            MemoryUtil.memFree(zBuf);
        }
        frameCount++;
    }

    private void writeExr(FloatBuffer rBuf, FloatBuffer gBuf, FloatBuffer bBuf,
                           FloatBuffer aBuf, FloatBuffer zBuf, int frameNumber) throws IOException {
        Path filePath = outputDir.resolve(String.format("%04d.exr", frameNumber));

        // Keep references to allocated native memory for cleanup
        List<ByteBuffer> nameBufs = new ArrayList<>();
        EXRHeader header = EXRHeader.calloc();
        EXRImage image = EXRImage.calloc();
        EXRChannelInfo.Buffer channelInfo = null;
        IntBuffer pixelTypes = null;
        IntBuffer requestedTypes = null;
        PointerBuffer imagePtrs = null;

        try {
            TinyEXR.InitEXRHeader(header);
            TinyEXR.InitEXRImage(image);

            // --- Channel names ---
            channelInfo = EXRChannelInfo.calloc(NUM_CHANNELS);
            for (int i = 0; i < NUM_CHANNELS; i++) {
                ByteBuffer nameBuf = MemoryUtil.memUTF8(CHANNEL_NAMES[i]);
                nameBufs.add(nameBuf);
                channelInfo.get(i).name(nameBuf);
            }
            header.channels(channelInfo);
            header.num_channels(NUM_CHANNELS);

            // --- Pixel types ---
            pixelTypes = MemoryUtil.memAllocInt(NUM_CHANNELS);
            requestedTypes = MemoryUtil.memAllocInt(NUM_CHANNELS);
            for (int i = 0; i < NUM_CHANNELS; i++) {
                pixelTypes.put(i, TinyEXR.TINYEXR_PIXELTYPE_FLOAT);
                // RGBA channels: HALF output; Depth: FLOAT output (ReplayMod pattern)
                requestedTypes.put(i, (i < 4) ? TinyEXR.TINYEXR_PIXELTYPE_HALF : TinyEXR.TINYEXR_PIXELTYPE_FLOAT);
            }
            pixelTypes.flip();
            requestedTypes.flip();
            header.pixel_types(pixelTypes);
            header.requested_pixel_types(requestedTypes);

            // --- Compression ---
            header.compression_type(TinyEXR.TINYEXR_COMPRESSIONTYPE_NONE);

            // --- Image ---
            image.width(width);
            image.height(height);
            image.num_channels(NUM_CHANNELS);

            // --- Image channel data pointers ---
            imagePtrs = MemoryUtil.memAllocPointer(NUM_CHANNELS);

            imagePtrs = MemoryUtil.memAllocPointer(NUM_CHANNELS);
            // A, B, G, R order — matches ReplayMod's proven channel layout
            imagePtrs.put(0, MemoryUtil.memAddress(aBuf));
            imagePtrs.put(1, MemoryUtil.memAddress(bBuf));
            imagePtrs.put(2, MemoryUtil.memAddress(gBuf));
            imagePtrs.put(3, MemoryUtil.memAddress(rBuf));
            imagePtrs.put(4, MemoryUtil.memAddress(zBuf));
            imagePtrs.flip();
            image.images(imagePtrs);

            // --- Write ---
            ByteBuffer pathBuf = MemoryUtil.memUTF8(filePath.toAbsolutePath().toString());
            PointerBuffer err = MemoryUtil.memAllocPointer(1);
            int result = TinyEXR.SaveEXRImageToFile(image, header, pathBuf, err);
            if (result != 0) {
                long errAddr = err.get(0);
                String error = errAddr != 0 ? MemoryUtil.memUTF8(errAddr) : "unknown error";
                MemoryUtil.memFree(err);
                MemoryUtil.memFree(pathBuf);
                throw new IOException("tinyexr SaveEXRImageToFile failed: " + error + " (code " + result + ")");
            }
            MemoryUtil.memFree(err);
            MemoryUtil.memFree(pathBuf);
        } finally {
            // Free structs and all manually-allocated memory
            // (don't call FreeEXRImage/FreeEXRHeader — they can double-free our allocations)
            if (imagePtrs != null) MemoryUtil.memFree(imagePtrs);
            if (pixelTypes != null) MemoryUtil.memFree(pixelTypes);
            if (requestedTypes != null) MemoryUtil.memFree(requestedTypes);
            for (ByteBuffer b : nameBufs) MemoryUtil.memFree(b);
            if (channelInfo != null) channelInfo.free();
            image.free();
            header.free();
        }
    }

    public int getFrameCount() { return frameCount; }

    @Override
    public void close() {}
}
