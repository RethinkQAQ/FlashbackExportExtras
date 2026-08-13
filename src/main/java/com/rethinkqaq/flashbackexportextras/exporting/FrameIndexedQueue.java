package com.rethinkqaq.flashbackexportextras.exporting;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

/**
 * Thread-safe ownership queue for asynchronous GPU frames.
 *
 * <p>The queue keeps frames ordered by their explicit export frame ID. It also
 * owns every submitted frame until it is polled, so duplicate, stale and reset
 * paths can release native memory in one place.</p>
 */
public final class FrameIndexedQueue<T> {
    private final Deque<T> frames = new ArrayDeque<>();
    private final ToLongFunction<T> frameId;
    private final Consumer<T> releaser;
    private final String label;
    private Throwable failure;

    public FrameIndexedQueue(String label, ToLongFunction<T> frameId, Consumer<T> releaser) {
        this.label = label;
        this.frameId = frameId;
        this.releaser = releaser;
    }

    public synchronized void submit(T frame) {
        long id = frameId.applyAsLong(frame);
        if (frames.isEmpty() || frameId.applyAsLong(frames.peekLast()) < id) {
            frames.addLast(frame);
            return;
        }

        Deque<T> later = new ArrayDeque<>();
        while (!frames.isEmpty() && frameId.applyAsLong(frames.peekLast()) > id) {
            later.addFirst(frames.removeLast());
        }
        if (!frames.isEmpty() && frameId.applyAsLong(frames.peekLast()) == id) {
            while (!later.isEmpty()) frames.addLast(later.removeFirst());
            releaser.accept(frame);
            fail(new IllegalStateException("Duplicate " + label + " frame " + id));
            return;
        }
        frames.addLast(frame);
        while (!later.isEmpty()) frames.addLast(later.removeFirst());
    }

    public synchronized T poll(long expectedFrameId) {
        throwIfFailed();
        T frame = prepareExpected(expectedFrameId);
        if (frame == null) return null;
        return frames.removeFirst();
    }

    public synchronized T peek(long expectedFrameId) {
        throwIfFailed();
        return prepareExpected(expectedFrameId);
    }

    private T prepareExpected(long expectedFrameId) {
        T frame = frames.peekFirst();
        if (frame == null || frameId.applyAsLong(frame) > expectedFrameId) return null;
        long actualFrameId = frameId.applyAsLong(frame);
        if (actualFrameId < expectedFrameId) {
            frames.removeFirst();
            releaser.accept(frame);
            throw new IllegalStateException("Stale " + label + " frame " + actualFrameId
                    + " while waiting for " + expectedFrameId);
        }
        return frame;
    }

    public synchronized int size() {
        return frames.size();
    }

    public synchronized void verifyComplete(long expectedFrameCount, long consumedFrameCount) {
        throwIfFailed();
        if (!frames.isEmpty() || consumedFrameCount != expectedFrameCount) {
            long next = frames.isEmpty() ? -1L : frameId.applyAsLong(frames.peekFirst());
            throw new IllegalStateException("Incomplete " + label + " readback: captured="
                    + expectedFrameCount + ", consumed=" + consumedFrameCount
                    + ", nextQueued=" + next);
        }
    }

    public synchronized void fail(Throwable throwable) {
        if (failure == null) failure = throwable;
    }

    public synchronized void throwIfFailed() {
        if (failure != null) throw new IllegalStateException(label + " GPU readback failed", failure);
    }

    public synchronized void reset() {
        T frame;
        while ((frame = frames.pollFirst()) != null) releaser.accept(frame);
        failure = null;
    }
}
