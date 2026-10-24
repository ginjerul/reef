package dev.reef.util;

import it.unimi.dsi.fastutil.PriorityQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import java.util.concurrent.locks.LockSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A single background thread that runs submitted jobs in order.
 * From Pufferfish (GPL-3.0), used by the async mob spawning patch.
 */
public final class AsyncExecutor implements Runnable {

    private static final Logger LOGGER = LogManager.getLogger("Reef");

    private final PriorityQueue<Runnable> jobs = new ObjectArrayFIFOQueue<>();
    private final Thread thread;
    private volatile boolean killswitch = false;

    public AsyncExecutor(String threadName) {
        this.thread = Thread.ofPlatform()
            .name(threadName)
            .priority(Thread.NORM_PRIORITY - 1)
            .daemon(true) // never keep the JVM alive on shutdown
            .unstarted(this);
    }

    public void start() {
        this.thread.start();
    }

    public boolean isRunning() {
        return this.thread.isAlive() && !this.killswitch;
    }

    public void shutdown(long millis) {
        this.killswitch = true;
        LockSupport.unpark(this.thread);
        try {
            this.thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void submit(Runnable runnable) {
        synchronized (this.jobs) {
            this.jobs.enqueue(runnable);
        }
        LockSupport.unpark(this.thread);
    }

    @Override
    public void run() {
        while (!this.killswitch) {
            try {
                Runnable runnable = null;
                synchronized (this.jobs) {
                    if (!this.jobs.isEmpty()) {
                        runnable = this.jobs.dequeue();
                    }
                }
                if (runnable == null) {
                    LockSupport.park();
                    continue;
                }
                runnable.run();
            } catch (Throwable t) {
                LOGGER.error("Failed to execute async job on thread {}", this.thread.getName(), t);
            }
        }
    }
}
