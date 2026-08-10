package com.rethinkqaq.flashbackplus.exporting;

import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.flashback.exporting.VideoWriter;
import com.rethinkqaq.flashbackplus.Flashbackplus;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * HDR VideoWriter for HDR10 export.
 *
 * Streams 16-bit RGBA frames (PQ-encoded, BT.2020 primaries) to FFmpeg
 * via stdin pipe. Frames are written immediately on addHdrFrame() and
 * freed right after — no accumulation in memory.
 */
public class HdrVideoWriter implements VideoWriter {

    private final Path outputPath;
    private final int width;
    private final int height;
    private final double framerate;
    private final int bitrate;
    private final int frameSize;
    private final byte[] frameBytes;  // reusable write buffer
    private Process ffmpegProcess;
    private OutputStream ffmpegStdin;
    private int frameCount;
    private boolean finished;
    private boolean pipeFailed;

    public HdrVideoWriter(Path outputPath, int width, int height, double framerate, int bitrate) throws IOException {
        this.outputPath = outputPath;
        this.width = width;
        this.height = height;
        this.framerate = framerate;
        this.bitrate = bitrate;
        this.frameSize = width * height * 8;
        this.frameBytes = new byte[frameSize];
        Files.createDirectories(outputPath.getParent());
        Flashbackplus.LOGGER.info("HDR encoder requested bitrate: {} bps ({} Mbps)",
                bitrate, bitrate / 1_000_000.0);
        Flashbackplus.LOGGER.info("HDR video export: {}x{} @ {}fps → {}",
                width, height, framerate, outputPath);
    }

    private void ensureStarted() throws IOException {
        if (ffmpegProcess != null) return;
        String outputStr = outputPath.toAbsolutePath().toString();
        ProcessBuilder pb = new ProcessBuilder(
            "ffmpeg",
            "-y",
            "-f", "rawvideo",
            "-pixel_format", "rgba64",
            "-video_size", width + "x" + height,
            "-framerate", String.valueOf((int) framerate),
            "-i", "pipe:0",
            "-c:v", "libx265",
            "-preset", "medium",
            "-b:v", String.valueOf(bitrate),
            "-minrate", String.valueOf(bitrate),
            "-maxrate", String.valueOf(bitrate),
            "-bufsize", String.valueOf(bitrate),
            "-pix_fmt", "yuv420p10le",
            "-color_primaries", "bt2020",
            "-color_trc", "smpte2084",
            "-colorspace", "bt2020c",
            "-color_range", "pc",
            "-x265-params", "hdr-opt=1:repeat-headers=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc"
                    + ":nal-hrd=cbr:vbv-maxrate=" + Math.max(1, bitrate / 1000)
                    + ":vbv-bufsize=" + Math.max(1, bitrate / 1000)
                    + ":filler=1",
            outputStr
        );
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        ffmpegProcess = pb.start();
        ffmpegStdin = ffmpegProcess.getOutputStream();
    }

    /**
     * Writes a 16-bit RGBA frame directly to FFmpeg stdin, then frees it.
     */
    public void addHdrFrame(ByteBuffer hdrData) {
        if (finished || pipeFailed) {
            MemoryUtil.memFree(hdrData);
            return;
        }
        boolean bufferOwned = true;
        try {
            ensureStarted();
            // Copy to reusable buffer and free immediately
            hdrData.rewind();
            hdrData.get(frameBytes);
            MemoryUtil.memFree(hdrData);
            bufferOwned = false;
            ffmpegStdin.write(frameBytes);
            frameCount++;
        } catch (IOException e) {
            pipeFailed = true;
            Flashbackplus.LOGGER.error("HDR export: pipe write failed at frame {}", frameCount, e);
            if (bufferOwned) MemoryUtil.memFree(hdrData);
            if (ffmpegProcess != null) ffmpegProcess.destroy();
        }
    }

    @Override
    public void encode(NativeImage image, FloatBuffer audioBuffer) {
        // HDR frames arrive through addHdrFrame()
    }

    @Override
    /*? if >=1.21.5 {*/
    /*public void finish(Consumer<String> statusConsumer) {
    *//*?} else {*/
    public void finish() {
    /*?}*/
        if (finished) return;
        finished = true;
        if (pipeFailed) {
            if (ffmpegProcess != null) ffmpegProcess.destroy();
            Flashbackplus.LOGGER.warn("HDR export aborted after FFmpeg pipe failure at frame {}", frameCount);
            return;
        }
        if (ffmpegStdin != null) {
            try { ffmpegStdin.flush(); ffmpegStdin.close(); } catch (IOException ignored) {}
        }
        if (ffmpegProcess != null) {
            try {
                int exitCode = ffmpegProcess.waitFor();
                if (exitCode != 0) {
                    Flashbackplus.LOGGER.warn("FFmpeg exited with code {}", exitCode);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ffmpegProcess.destroy();
            }
        }
        Flashbackplus.LOGGER.info("HDR export: {} frames → {}", frameCount, outputPath);
    }

    @Override
    public void close() {
        finished = true;
        if (ffmpegProcess != null) ffmpegProcess.destroy();
    }
}
