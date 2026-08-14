/*
 * Flashback Export Extras
 * Copyright (C) RethinkQAQ
 *
 * This file is part of Flashback Export Extras.
 *
 * Flashback Export Extras is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Flashback Export Extras is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with Flashback Export Extras. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package com.rethinkqaq.flashbackexportextras.exporting;

import com.mojang.blaze3d.platform.NativeImage;
import com.rethinkqaq.flashbackexportextras.Flashbackplus;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyexr.EXRChannelInfo;
import org.lwjgl.util.tinyexr.EXRAttribute;
import org.lwjgl.util.tinyexr.EXRHeader;
import org.lwjgl.util.tinyexr.EXRImage;
import org.lwjgl.util.tinyexr.TinyEXR;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-layer OpenEXR writer using LWJGL tinyexr bindings.
 * Same approach as ReplayMod — SaveEXRImageToFile with EXRHeader/EXRImage.
 *
 * Perf: All native buffers and EXR structs are pre-allocated once and reused
 * across frames to eliminate per-frame allocation overhead (~40MB/frame).
 */
public class MultiLayerExrWriter implements AutoCloseable {

    private static final int NUM_CHANNELS = 5;
    private static final float INV_255 = 1.0f / 255.0f;

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
    private final boolean sceneLinearHdr;
    private int frameCount;
    private boolean closed = false;
    private int depthDebugFrame;
    private int hdrDebugFrame;

    // === Pre-allocated pixel buffers (reused every frame) ===
    private final FloatBuffer rBuf, gBuf, bBuf, aBuf, zBuf;

    // === Pre-allocated EXR data structures ===
    private final EXRHeader header;
    private final EXRImage image;
    private final EXRChannelInfo.Buffer channelInfo;
    private final IntBuffer pixelTypes;
    private final IntBuffer requestedTypes;
    private final PointerBuffer imagePtrs;
    private final List<ByteBuffer> nameBufs;
    private final EXRAttribute.Buffer customAttributes;
    private final List<ByteBuffer> customAttributeBuffers;

    public MultiLayerExrWriter(Path outputDir, int width, int height, boolean linearizeDepth,
                               boolean sceneLinearHdr) throws IOException {
        this.outputDir = outputDir;
        this.width = width;
        this.height = height;
        this.linearizeDepth = linearizeDepth;
        this.sceneLinearHdr = sceneLinearHdr;
        this.frameCount = 0;
        Files.createDirectories(outputDir);

        int pixelCount = width * height;

        // --- Pre-allocate pixel buffers (5 × width × height × 4 bytes) ---
        this.rBuf = MemoryUtil.memAllocFloat(pixelCount);
        this.gBuf = MemoryUtil.memAllocFloat(pixelCount);
        this.bBuf = MemoryUtil.memAllocFloat(pixelCount);
        this.aBuf = MemoryUtil.memAllocFloat(pixelCount);
        this.zBuf = MemoryUtil.memAllocFloat(pixelCount);

        // --- Pre-allocate EXR data structures ---
        this.header = EXRHeader.calloc();
        this.image = EXRImage.calloc();
        this.channelInfo = EXRChannelInfo.calloc(NUM_CHANNELS);
        this.pixelTypes = MemoryUtil.memAllocInt(NUM_CHANNELS);
        this.requestedTypes = MemoryUtil.memAllocInt(NUM_CHANNELS);
        this.imagePtrs = MemoryUtil.memAllocPointer(NUM_CHANNELS);
        this.nameBufs = new ArrayList<>(NUM_CHANNELS);
        this.customAttributeBuffers = new ArrayList<>();

        // --- Pre-configure immutable channel metadata ---
        for (int i = 0; i < NUM_CHANNELS; i++) {
            ByteBuffer nameBuf = MemoryUtil.memUTF8(CHANNEL_NAMES[i]);
            nameBufs.add(nameBuf);
            channelInfo.get(i).name(nameBuf);
            pixelTypes.put(i, TinyEXR.TINYEXR_PIXELTYPE_FLOAT);
            // RGBA → HALF output; Depth → FLOAT output (ReplayMod pattern)
            requestedTypes.put(i, (i < 4) ? TinyEXR.TINYEXR_PIXELTYPE_HALF : TinyEXR.TINYEXR_PIXELTYPE_FLOAT);
        }
        pixelTypes.flip();
        requestedTypes.flip();

        // --- Pre-set image pointer table (native addresses are stable) ---
        // Channel order: A, B, G, R, Z
        imagePtrs.put(0, MemoryUtil.memAddress(aBuf));
        imagePtrs.put(1, MemoryUtil.memAddress(bBuf));
        imagePtrs.put(2, MemoryUtil.memAddress(gBuf));
        imagePtrs.put(3, MemoryUtil.memAddress(rBuf));
        imagePtrs.put(4, MemoryUtil.memAddress(zBuf));
        imagePtrs.flip();

        if (sceneLinearHdr) {
            customAttributes = EXRAttribute.calloc(1);
            ByteBuffer attributeName = MemoryUtil.memUTF8("chromaticities");
            ByteBuffer attributeType = MemoryUtil.memUTF8("chromaticities");
            ByteBuffer chromaticities = MemoryUtil.memAlloc(8 * Float.BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN);
            // Rec.709/sRGB primaries and D65 white, in OpenEXR chromaticities order.
            chromaticities.putFloat(0.6400f).putFloat(0.3300f);
            chromaticities.putFloat(0.3000f).putFloat(0.6000f);
            chromaticities.putFloat(0.1500f).putFloat(0.0600f);
            chromaticities.putFloat(0.3127f).putFloat(0.3290f).flip();
            customAttributes.get(0)
                    .name(attributeName)
                    .type(attributeType)
                    .value(chromaticities);
            customAttributeBuffers.add(attributeName);
            customAttributeBuffers.add(attributeType);
            customAttributeBuffers.add(chromaticities);
        } else {
            customAttributes = null;
        }
    }

    /**
     * Writes one multi-layer EXR frame.
     * Fills pre-allocated buffers with new data, then calls tinyexr.
     */
    public void writeFrame(NativeImage colorImage, FloatBuffer depthBuffer, int frameNumber,
                           float zNear, float zFar) throws IOException {
        fillSdrColor(colorImage);
        fillDepth(depthBuffer, zNear, zFar);
        writeExr(frameNumber);
        frameCount++;
    }

    /** Writes scene-linear Rec.709 RGBA16F color with the matching depth frame. */
    public void writeHdrFrame(ByteBuffer rgba16f, FloatBuffer depthBuffer, int frameNumber,
                              float zNear, float zFar) throws IOException {
        fillSceneLinearHdrColor(rgba16f);
        fillDepth(depthBuffer, zNear, zFar);
        writeExr(frameNumber);
        frameCount++;
    }

    /**
     * Fills the pre-allocated float buffers from NativeImage and depth buffer.
     *
     * Uses getPixelsRGBA() for a single bulk copy (one memcpy) instead of
     * width*height individual JNI calls to getPixelRGBA(x,y).
     */
    private void fillSdrColor(NativeImage colorImage) {
        float inv255 = INV_255;
        int pixelCount = width * height;

        // --- Pass 1: Bulk copy pixels, then extract RGBA in pure Java ---
        /*? if >=1.21.4 {*/
        /*int[] pixelArray = colorImage.getPixelsABGR();
        *//*?} else {*/
        int[] pixelArray = colorImage.getPixelsRGBA();
        /*?}*/
        for (int i = 0; i < pixelCount; i++) {
            int pixel = pixelArray[i];
            // 0xAABBGGRR → individual float channels
            rBuf.put(i, (pixel & 0xFF) * inv255);
            gBuf.put(i, ((pixel >> 8) & 0xFF) * inv255);
            bBuf.put(i, ((pixel >> 16) & 0xFF) * inv255);
            // Force full opacity: Iris shaders may write non-0xFF alpha for compositing/HDR
            aBuf.put(i, 1.0f);
        }
    }

    private void fillSceneLinearHdrColor(ByteBuffer rgba16f) {
        int pixelCount = width * height;
        int requiredBytes = pixelCount * 8;
        ByteBuffer data = rgba16f.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        data.rewind();
        if (data.remaining() < requiredBytes) {
            throw new IllegalArgumentException("RGBA16F frame is too small: "
                    + data.remaining() + " < " + requiredBytes);
        }
        for (int i = 0; i < pixelCount; i++) {
            int offset = i * 8;
            rBuf.put(i, halfToFloat(data.getShort(offset)));
            gBuf.put(i, halfToFloat(data.getShort(offset + 2)));
            bBuf.put(i, halfToFloat(data.getShort(offset + 4)));
            aBuf.put(i, halfToFloat(data.getShort(offset + 6)));
        }
        logSceneLinearHdrOutput();
    }

    private void logSceneLinearHdrOutput() {
        int frame = hdrDebugFrame++;
        if (frame >= 3 && frame % 30 != 0) return;
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        int finite = 0;
        int aboveOne = 0;
        int count = width * height * 3;
        for (int i = 0; i < width * height; i++) {
            float r = rBuf.get(i);
            float g = gBuf.get(i);
            float b = bBuf.get(i);
            for (int channel = 0; channel < 3; channel++) {
                float value = channel == 0 ? r : channel == 1 ? g : b;
                if (!Float.isFinite(value)) continue;
                min = Math.min(min, value);
                max = Math.max(max, value);
                finite++;
                if (value > 1.0f) aboveOne++;
            }
        }
        Flashbackplus.LOGGER.info(
                "EXR scene-linear HDR #{}: finite={}/{}, min={}, max={}, samplesAboveOne={}",
                frame, finite, count, min, max, aboveOne);
    }

    private static float halfToFloat(short half) {
        int bits = half & 0xffff;
        int sign = (bits & 0x8000) << 16;
        int exponent = (bits >>> 10) & 0x1f;
        int mantissa = bits & 0x03ff;
        if (exponent == 0) {
            if (mantissa == 0) return Float.intBitsToFloat(sign);
            while ((mantissa & 0x0400) == 0) {
                mantissa <<= 1;
                exponent--;
            }
            exponent++;
            mantissa &= ~0x0400;
        } else if (exponent == 31) {
            return Float.intBitsToFloat(sign | 0x7f800000 | (mantissa << 13));
        }
        int floatExponent = exponent + (127 - 15);
        return Float.intBitsToFloat(sign | (floatExponent << 23) | (mantissa << 13));
    }

    private void fillDepth(FloatBuffer depthBuffer, float zNear, float zFar) {

        // --- Pass 2: Fill depth (Y-flipped from GL bottom-up) ---
        if (linearizeDepth) {
            float znear = zNear;
            float zfar = zFar;
            float twoZnZf = 2.0f * znear * zfar;
            float zfMinusZn = zfar - znear;
            float zfPlusZn = zfar + znear;

            for (int y = 0; y < height; y++) {
                int dstIdx = y * width;
                int srcY = height - 1 - y;
                for (int x = 0; x < width; x++, dstIdx++) {
                    float depth = depthBuffer.get(srcY * width + x);
                    depth = twoZnZf / (zfPlusZn - (2.0f * depth - 1.0f) * zfMinusZn);
                    zBuf.put(dstIdx, depth);
                }
            }
        } else {
            for (int y = 0; y < height; y++) {
                int dstIdx = y * width;
                int srcY = height - 1 - y;
                for (int x = 0; x < width; x++, dstIdx++) {
                    zBuf.put(dstIdx, depthBuffer.get(srcY * width + x));
                }
            }
        }

        logDepthOutput(zNear, zFar);
    }

    private void logDepthOutput(float zNear, float zFar) {
        int frame = depthDebugFrame++;
        if (frame >= 3 && frame % 30 != 0) return;

        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        int finite = 0;
        int count = zBuf.capacity();
        for (int i = 0; i < count; i++) {
            float value = zBuf.get(i);
            if (Float.isFinite(value)) {
                min = Math.min(min, value);
                max = Math.max(max, value);
                finite++;
            }
        }

        int center = Math.max(0, Math.min(count - 1, (height / 2) * width + width / 2));
        int quarter = Math.max(0, Math.min(count - 1, (height / 4) * width + width / 4));
        com.rethinkqaq.flashbackexportextras.Flashbackplus.LOGGER.info(
                "EXR depth output #{}: linearize={}, near={}, far={}, finite={}/{}, min={}, max={}, q1={}, center={}, q3={}",
                frame, linearizeDepth, zNear, zFar, finite, count, min, max,
                zBuf.get(quarter), zBuf.get(center), zBuf.get(Math.max(0, count - 1 - quarter)));
    }

    /**
     * Writes the current buffer contents to an EXR file.
     * Reconfigures header/image each call (Init zeros fields; we re-apply).
     */
    private void writeExr(int frameNumber) throws IOException {
        Path filePath = outputDir.resolve(String.format("%04d.exr", frameNumber));

        // Reset header/image to zero (InitEXR does memset), then re-apply config
        TinyEXR.InitEXRHeader(header);
        TinyEXR.InitEXRImage(image);

        header.channels(channelInfo);
        header.num_channels(NUM_CHANNELS);
        header.pixel_types(pixelTypes);
        header.requested_pixel_types(requestedTypes);
        if (sceneLinearHdr) {
            header.num_custom_attributes(1);
            header.custom_attributes(customAttributes);
        }
        // Lossless ZIP compression keeps the Blender-oriented multi-layer
        // layout intact while substantially reducing disk bandwidth.
        header.compression_type(TinyEXR.TINYEXR_COMPRESSIONTYPE_ZIP);

        image.width(width);
        image.height(height);
        image.num_channels(NUM_CHANNELS);
        imagePtrs.rewind();
        image.images(imagePtrs);

        ByteBuffer pathBuf = MemoryUtil.memUTF8(filePath.toAbsolutePath().toString());
        PointerBuffer err = MemoryUtil.memAllocPointer(1);
        try {
            int result = TinyEXR.SaveEXRImageToFile(image, header, pathBuf, err);
            if (result != 0) {
                long errAddr = err.get(0);
                String error = errAddr != 0 ? MemoryUtil.memUTF8(errAddr) : "unknown error";
                throw new IOException("tinyexr SaveEXRImageToFile failed: " + error + " (code " + result + ")");
            }
        } finally {
            MemoryUtil.memFree(err);
            MemoryUtil.memFree(pathBuf);
        }
    }

    public int getFrameCount() { return frameCount; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;

        // Free all pre-allocated native memory
        MemoryUtil.memFree(rBuf);
        MemoryUtil.memFree(gBuf);
        MemoryUtil.memFree(bBuf);
        MemoryUtil.memFree(aBuf);
        MemoryUtil.memFree(zBuf);
        MemoryUtil.memFree(imagePtrs);
        MemoryUtil.memFree(pixelTypes);
        MemoryUtil.memFree(requestedTypes);
        for (ByteBuffer b : nameBufs) MemoryUtil.memFree(b);
        for (ByteBuffer b : customAttributeBuffers) MemoryUtil.memFree(b);
        if (customAttributes != null) customAttributes.free();
        channelInfo.free();
        image.free();
        header.free();
    }
}
