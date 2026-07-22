package com.rethinkqaq.flashbackplus.exporting;

import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.flashback.exporting.VideoWriter;
import com.rethinkqaq.flashbackplus.Flashbackplus;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * HDR VideoWriter for HDR10 export.
 *
 * Collects 16-bit RGBA frames (PQ-encoded, BT.2020 primaries) and
 * encodes them on finish() via an external FFmpeg process.
 */
public class HdrVideoWriter implements VideoWriter {

    private final Path outputPath;
    private final int width;
    private final int height;
    private final double framerate;
    private final List<ByteBuffer> frames = new ArrayList<>();
    private boolean finished;

    public HdrVideoWriter(Path outputPath, int width, int height, double framerate) throws IOException {
        this.outputPath = outputPath;
        this.width = width;
        this.height = height;
        this.framerate = framerate;
        Files.createDirectories(outputPath.getParent());
        Flashbackplus.LOGGER.info("HDR video export: {}x{} @ {}fps → {}",
                width, height, framerate, outputPath);
    }

    /** Adds a 16-bit RGBA HDR frame (called from MixinExportJob). */
    public void addHdrFrame(ByteBuffer hdrData) {
        if (!finished) {
            frames.add(hdrData);
        }
    }

    public int getFrameCount() {
        return frames.size();
    }

    /**
     * Standard VideoWriter.encode — ignored in HDR mode.
     * Actual frame data comes via addHdrFrame().
     */
    @Override
    public void encode(NativeImage image, FloatBuffer audioBuffer) {
        // HDR frames arrive through addHdrFrame() — this is the 8-bit path (unused for HDR)
    }

    @Override
    public void finish() {
        if (finished) return;
        finished = true;

        if (frames.isEmpty()) {
            Flashbackplus.LOGGER.warn("HDR export: no frames captured");
            return;
        }

        Flashbackplus.LOGGER.info("HDR export: encoding {} frames via FFmpeg...", frames.size());

        try {
            encodeWithFfmpeg();
        } catch (IOException e) {
            Flashbackplus.LOGGER.error("HDR export: FFmpeg encoding failed", e);
        } finally {
            // Free all frame buffers
            for (ByteBuffer buf : frames) {
                org.lwjgl.system.MemoryUtil.memFree(buf);
            }
            frames.clear();
        }

        Flashbackplus.LOGGER.info("HDR export: done → {}", outputPath);
    }

    private void encodeWithFfmpeg() throws IOException {
        // Build FFmpeg command
        String outputStr = outputPath.toAbsolutePath().toString();
        int frameSize = width * height * 8; // RGBA16 = 8 bytes/pixel

        ProcessBuilder pb = new ProcessBuilder(
            "ffmpeg",
            "-y",
            "-f", "rawvideo",
            "-pixel_format", "rgba64",
            "-video_size", width + "x" + height,
            "-framerate", String.valueOf((int) framerate),
            "-i", "pipe:0",
            "-c:v", "libx265",
            "-crf", "16",
            "-preset", "medium",
            "-pix_fmt", "yuv420p10le",
            "-color_primaries", "bt2020",
            "-color_trc", "smpte2084",
            "-colorspace", "bt2020c",
            "-color_range", "pc",
            "-x265-params", "hdr-opt=1:repeat-headers=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc",
            outputStr
        );

        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process = pb.start();

        // Write all frames to FFmpeg stdin in a background thread
        new Thread(() -> {
            try {
                var out = process.getOutputStream();
                byte[] frameBytes = new byte[frameSize];
                for (ByteBuffer buf : frames) {
                    buf.rewind();
                    buf.get(frameBytes);
                    out.write(frameBytes);
                }
                out.flush();
                out.close();
            } catch (IOException e) {
                Flashbackplus.LOGGER.error("HDR export: pipe write failed", e);
            }
        }, "HDR-FFmpeg-writer").start();

        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                Flashbackplus.LOGGER.warn("FFmpeg exited with code {}", exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
        }
    }

    @Override
    public void close() {
        finished = true;
        for (ByteBuffer buf : frames) {
            org.lwjgl.system.MemoryUtil.memFree(buf);
        }
        frames.clear();
    }
}
