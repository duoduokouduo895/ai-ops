package com.dduo.faultlab;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "faultlab", name = "role", havingValue = "payment")
public class ThreadPoolFaultManager {

    private final FaultMetrics metrics;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), new ThreadPoolExecutor.AbortPolicy());
    private CountDownLatch releaseLatch;

    public ThreadPoolFaultManager(FaultMetrics metrics) {
        this.metrics = metrics;
        updateMetrics();
    }

    public synchronized void enable() {
        if (releaseLatch != null) {
            return;
        }
        // Capture one immutable latch for all four tasks. Reading the mutable field from each
        // lambda makes the admission sequence race with disable()/shutdown instrumentation.
        var latch = new CountDownLatch(1);
        releaseLatch = latch;
        for (var count = 0; count < 4; count++) {
            executor.execute(() -> awaitRelease(latch));
        }
        updateMetrics();
    }

    public synchronized void disable() {
        if (releaseLatch != null) {
            releaseLatch.countDown();
            releaseLatch = null;
        }
        executor.purge();
        updateMetrics();
    }

    public synchronized void submitPaymentWork() {
        // The executor belongs only to the controlled saturation experiment. Letting
        // normal payment requests enqueue bookkeeping tasks makes a later enable()
        // race with residual OpenTelemetry-wrapped work and can turn the control
        // endpoint itself into a 500 response instead of a deterministic fault.
        if (releaseLatch == null) {
            updateMetrics();
            return;
        }
        try {
            executor.execute(() -> {
                // Reaching this task means the controlled pool was not saturated.
            });
            updateMetrics();
        } catch (RejectedExecutionException exception) {
            metrics.recordThreadPoolRejected();
            updateMetrics();
            throw exception;
        }
    }

    @PreDestroy
    public synchronized void destroy() {
        disable();
        executor.shutdownNow();
    }

    private void awaitRelease(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            updateMetrics();
        }
    }

    private void updateMetrics() {
        metrics.setThreadPoolState(executor.getActiveCount(), executor.getQueue().size());
    }
}
