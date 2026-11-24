package dev.agentworkshq.eval.core.runner;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class TimeBox {

    enum State {
        DONE,
        FAILED,
        TIMED_OUT
    }

    record Outcome<T>(State state, T value, Throwable error) {
    }

    private final ExecutorService calls;
    private final long graceMillis;
    private final AtomicInteger abandoned = new AtomicInteger();

    TimeBox(long graceMillis) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "eval-runner-call-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.calls = Executors.newCachedThreadPool(factory);
        this.graceMillis = graceMillis;
    }

    <T> Outcome<T> run(Callable<T> body, long timeoutMillis) throws InterruptedException {
        CountDownLatch ended = new CountDownLatch(1);
        AtomicBoolean started = new AtomicBoolean();
        Future<T> future = calls.submit(() -> {
            started.set(true);
            try {
                return body.call();
            } finally {
                ended.countDown();
            }
        });
        try {
            return new Outcome<>(State.DONE, future.get(timeoutMillis, TimeUnit.MILLISECONDS), null);
        } catch (ExecutionException e) {
            return new Outcome<>(State.FAILED, null, e.getCause());
        } catch (TimeoutException e) {
            future.cancel(true);
            if (started.get() && !ended.await(graceMillis, TimeUnit.MILLISECONDS)) {
                abandoned.incrementAndGet();
            }
            return new Outcome<>(State.TIMED_OUT, null, null);
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        }
    }

    int abandonedCalls() {
        return abandoned.get();
    }

    void close() {
        calls.shutdownNow();
        if (abandoned.get() == 0) {
            try {
                calls.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
