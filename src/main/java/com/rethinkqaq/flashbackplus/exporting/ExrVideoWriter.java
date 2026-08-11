package com.rethinkqaq.flashbackplus.exporting;

import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.flashback.exporting.VideoWriter;
import com.rethinkqaq.flashbackplus.FlashbackPlusConfig;
import com.rethinkqaq.flashbackplus.Flashbackplus;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * VideoWriter implementation that writes multi-layer OpenEXR frames
 * (color + depth) instead of a video file.
 */
public class ExrVideoWriter implements VideoWriter {

    private static final int QUEUE_CAPACITY = 8;
    private static final int WRITER_COUNT = 2;
    private static final FramePacket STOP = new FramePacket(-1, null, null, 0.05f, 1000.0f);

    private final MultiLayerExrWriter[] exrWriters;
    private final ArrayBlockingQueue<FramePacket> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final CountDownLatch writerStopped = new CountDownLatch(WRITER_COUNT);
    private final AtomicReference<Throwable> writerFailure = new AtomicReference<>();
    private final Thread[] writerThreads = new Thread[WRITER_COUNT];
    private final Deque<NativeImage> pendingColors = new ArrayDeque<>();
    private volatile boolean accepting = true;
    private boolean finished;
    private long nextFrameId;
    private long encodedFrameCount;

    public ExrVideoWriter(Path outputDir, int width, int height) throws IOException {
        this.exrWriters = new MultiLayerExrWriter[WRITER_COUNT];
        try {
            for (int i = 0; i < WRITER_COUNT; i++) {
                exrWriters[i] = new MultiLayerExrWriter(outputDir, width, height,
                        FlashbackPlusConfig.INSTANCE.depthLinearizeWorldSpace);
            }
        } catch (IOException | RuntimeException e) {
            for (MultiLayerExrWriter writer : exrWriters) {
                if (writer != null) writer.close();
            }
            throw e;
        }
        for (int i = 0; i < WRITER_COUNT; i++) {
            final int workerIndex = i;
            Thread writerThread = new Thread(() -> writeLoop(workerIndex),
                    "flashbackplus-exr-writer-" + i);
            writerThread.setDaemon(true);
            writerThread.start();
            writerThreads[i] = writerThread;
        }
        Flashbackplus.LOGGER.info("EXR writers started: output={}, workers={}, queueCapacity={}",
                outputDir, WRITER_COUNT, QUEUE_CAPACITY);
    }

    @Override
    public void encode(NativeImage colorImage, FloatBuffer audioBuffer) {
        if (!accepting) {
            colorImage.close();
            throw new IllegalStateException("EXR writer is already finished");
        }

        pendingColors.addLast(colorImage);
        drainPairs();
    }

    private void drainPairs() {
        while (!pendingColors.isEmpty()) {
            DepthCaptureState.DepthFrame depthFrame;
            synchronized (DepthCaptureState.depthQueue) {
                // Never pair by completion order. GPU readback can complete late,
                // and a FIFO pairing would silently attach another frame's depth.
                while (!DepthCaptureState.depthQueue.isEmpty()
                        && DepthCaptureState.depthQueue.peekFirst().frameId < nextFrameId) {
                    DepthCaptureState.DepthFrame stale = DepthCaptureState.depthQueue.pollFirst();
                    DepthCaptureState.releaseBuffer(stale.data);
                    Flashbackplus.LOGGER.warn("Dropping stale EXR depth frame {} (expected {})",
                            stale.frameId, nextFrameId);
                }
                if (DepthCaptureState.depthQueue.isEmpty()
                        || DepthCaptureState.depthQueue.peekFirst().frameId != nextFrameId) {
                    break;
                }
                depthFrame = DepthCaptureState.depthQueue.pollFirst();
            }
            if (depthFrame == null) break;

            NativeImage colorImage = pendingColors.removeFirst();
            FramePacket packet = new FramePacket((int) nextFrameId++, colorImage, depthFrame.data,
                    depthFrame.zNear, depthFrame.zFar);
            try {
                queue.put(packet);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                packet.close();
                throw new IllegalStateException("Interrupted while waiting for EXR writer", e);
            }
            encodedFrameCount++;
        }
    }

    private void discardPendingColors() {
        NativeImage color;
        while ((color = pendingColors.pollFirst()) != null) {
            color.close();
        }
    }

    private void writeLoop(int workerIndex) {
        try {
            while (true) {
                FramePacket packet = queue.take();
                if (packet == STOP) return;
                try {
                    exrWriters[workerIndex].writeFrame(packet.color, packet.depth, packet.frameId,
                            packet.zNear, packet.zFar);
                } catch (Throwable t) {
                    writerFailure.compareAndSet(null, t);
                    return;
                } finally {
                    packet.close();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writerFailure.compareAndSet(null, e);
        } finally {
            // Do not drain the shared queue here. Another worker may still
            // need its STOP sentinel; draining it here can make that worker
            // block forever in queue.take(). Remaining packets are cleaned
            // after all workers have stopped in finishInternal().
            writerStopped.countDown();
        }
    }

    private void finishInternal() {
        if (finished) return;
        finished = true;
        accepting = false;
        Flashbackplus.LOGGER.info("EXR finish: draining pending pairs");
        try {
            drainPairs();
            discardPendingColors();
            Flashbackplus.LOGGER.info("EXR finish: queue={}, sending {} stop signals",
                    queue.size(), WRITER_COUNT);
            // Every worker has its own blocking take(); one sentinel is not
            // enough to release all workers during finalization.
            for (int i = 0; i < WRITER_COUNT; i++) {
                if (!queue.offer(STOP, 30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out queueing EXR writer stop signal");
                }
            }
            Flashbackplus.LOGGER.info("EXR finish: waiting for {} writer threads", WRITER_COUNT);
            if (!writerStopped.await(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for EXR writer threads");
            }
            Flashbackplus.LOGGER.info("EXR finish: writer threads stopped");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writerFailure.compareAndSet(null, e);
            for (Thread thread : writerThreads) {
                if (thread != null) thread.interrupt();
            }
        } catch (RuntimeException e) {
            writerFailure.compareAndSet(null, e);
            for (Thread thread : writerThreads) {
                if (thread != null) thread.interrupt();
            }
        } finally {
            FramePacket packet;
            while ((packet = queue.poll()) != null) {
                if (packet != STOP) packet.close();
            }
            Flashbackplus.LOGGER.info("EXR finish: closing native writers");
            for (MultiLayerExrWriter writer : exrWriters) {
                if (writer != null) writer.close();
            }
            Flashbackplus.LOGGER.info("EXR finish: native writers closed");
        }

        Throwable failure = writerFailure.get();
        if (failure != null) {
            Flashbackplus.LOGGER.error("EXR writer failed", failure);
        }
        Flashbackplus.LOGGER.info("EXR writer finished: encoded={}, pendingColors={}, pendingDepth={}",
                encodedFrameCount, pendingColors.size(), DepthCaptureState.depthQueue.size());
    }

    @Override
    /*? if >=1.21.5 {*/
    /*public void finish(Consumer<String> statusConsumer) {
    *//*?} else {*/
    public void finish() {
    /*?}*/
        // EXR uses the main Flashback export progress. Do not emit a second
        // progress phase for the camera path or the EXR writer queue.
        finishInternal();
    }

    @Override
    public void close() {
        finishInternal();
    }

    private static final class FramePacket {
        private final int frameId;
        private final NativeImage color;
        private final FloatBuffer depth;
        private final float zNear;
        private final float zFar;

        private FramePacket(int frameId, NativeImage color, FloatBuffer depth, float zNear, float zFar) {
            this.frameId = frameId;
            this.color = color;
            this.depth = depth;
            this.zNear = zNear;
            this.zFar = zFar;
        }

        private void close() {
            if (color != null) color.close();
            if (depth != null) DepthCaptureState.releaseBuffer(depth);
        }
    }
}
