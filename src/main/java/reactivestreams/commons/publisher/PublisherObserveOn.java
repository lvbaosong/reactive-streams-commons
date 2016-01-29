package reactivestreams.commons.publisher;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactivestreams.commons.flow.Fuseable;
import reactivestreams.commons.flow.Fuseable.FusionMode;
import reactivestreams.commons.util.BackpressureHelper;
import reactivestreams.commons.util.EmptySubscription;
import reactivestreams.commons.util.ExceptionHelper;
import reactivestreams.commons.util.SubscriptionHelper;

/**
 * Emits events on a different thread specified by a scheduler callback.
 *
 * @param <T> the value type
 */
public final class PublisherObserveOn<T> extends PublisherSource<T, T> {

    final Function<Runnable, Runnable> scheduler;
    
    final boolean delayError;
    
    final Supplier<? extends Queue<T>> queueSupplier;
    
    final int prefetch;
    
    public PublisherObserveOn(
            Publisher<? extends T> source, Function<Runnable, Runnable> scheduler, 
            boolean delayError,
            int prefetch,
            Supplier<? extends Queue<T>> queueSupplier) {
        super(source);
        if (prefetch <= 0) {
            throw new IllegalArgumentException("prefetch > 0 required but it was " + prefetch);
        }
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.delayError = delayError;
        this.prefetch = prefetch;
        this.queueSupplier = Objects.requireNonNull(queueSupplier, "queueSupplier");
    }

    public PublisherObserveOn(
            Publisher<? extends T> source, 
            ExecutorService executor, 
            boolean delayError,
            int prefetch,
            Supplier<? extends Queue<T>> queueSupplier) {
        super(source);
        if (prefetch <= 0) {
            throw new IllegalArgumentException("prefetch > 0 required but it was " + prefetch);
        }
        Objects.requireNonNull(executor, "executor");
        this.scheduler = new ExecutorServiceWorker(executor);

        this.delayError = delayError;
        this.prefetch = prefetch;
        this.queueSupplier = Objects.requireNonNull(queueSupplier, "queueSupplier");
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        if (PublisherSubscribeOn.trySupplierScheduleOn(source, s, scheduler, true)) {
            return;
        }
        source.subscribe(new PublisherObserveOnSubscriber<>(s, scheduler, delayError, prefetch, queueSupplier));
    }
    
    static final class PublisherObserveOnSubscriber<T>
    implements Subscriber<T>, Subscription, Runnable {
        
        final Subscriber<? super T> actual;
        
        final Function<Runnable, Runnable> scheduler;
        
        final boolean delayError;
        
        final int prefetch;
        
        final Supplier<? extends Queue<T>> queueSupplier;
        
        Subscription s;
        
        Queue<T> queue;
        
        volatile boolean cancelled;
        
        volatile boolean done;
        
        Throwable error;
        
        volatile int wip;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<PublisherObserveOnSubscriber> WIP =
                AtomicIntegerFieldUpdater.newUpdater(PublisherObserveOnSubscriber.class, "wip");

        volatile long requested;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<PublisherObserveOnSubscriber> REQUESTED =
                AtomicLongFieldUpdater.newUpdater(PublisherObserveOnSubscriber.class, "requested");

        volatile IndexedCancellable task;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<PublisherObserveOnSubscriber,IndexedCancellable> TASK =
                AtomicReferenceFieldUpdater.newUpdater(PublisherObserveOnSubscriber.class, IndexedCancellable.class, "task");
        
        static final IndexedCancellable TERMINATED = new TerminatedIndexedCancellable();
        
        long index;
        
        int sourceMode;
        
        static final int NORMAL = 0;
        static final int SYNC = 1;
        static final int ASYNC = 2;
        
        public PublisherObserveOnSubscriber(
                Subscriber<? super T> actual,
                Function<Runnable, Runnable> scheduler,
                boolean delayError,
                int prefetch,
                Supplier<? extends Queue<T>> queueSupplier) {
            this.actual = actual;
            this.scheduler = scheduler;
            this.delayError = delayError;
            this.prefetch = prefetch;
            this.queueSupplier = queueSupplier;
        }
        
        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                this.s = s;
                
                if (s instanceof Fuseable.QueueSubscription) {
                    @SuppressWarnings("unchecked")
                    Fuseable.QueueSubscription<T> f = (Fuseable.QueueSubscription<T>) s;
                    
                    FusionMode m = f.requestFusion(FusionMode.ANY);
                    
                    if (m == FusionMode.SYNC) {
                        queue = f;
                        sourceMode = SYNC;
                        done = true;
                        
                        actual.onSubscribe(this);
                        return;
                    } else
                    if (m == FusionMode.ASYNC) {
                        queue = f;
                        sourceMode = ASYNC;
                        
                    } else {
                        try {
                            queue = queueSupplier.get();
                        } catch (Throwable e) {
                            ExceptionHelper.throwIfFatal(e);
                            s.cancel();
                            
                            EmptySubscription.error(actual, e);
                            return;
                        }
                    }
                } else {
                    try {
                        queue = queueSupplier.get();
                    } catch (Throwable e) {
                        ExceptionHelper.throwIfFatal(e);
                        s.cancel();
                        
                        EmptySubscription.error(actual, e);
                        return;
                    }
                }
                
                actual.onSubscribe(this);
                
                s.request(prefetch);
            }
        }
        
        @Override
        public void onNext(T t) {
            if (sourceMode == ASYNC) {
                trySchedule();
                return;
            }
            if (!queue.offer(t)) {
                error = new IllegalStateException("Queue is full?!");
                done = true;
            }
            trySchedule();
        }
        
        @Override
        public void onError(Throwable t) {
            error = t;
            done = true;
            trySchedule();
        }
        
        @Override
        public void onComplete() {
            done = true;
            trySchedule();
        }
        
        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                BackpressureHelper.addAndGet(REQUESTED, this, n);
                trySchedule();
            }
        }
        
        @Override
        public void cancel() {
            if (cancelled) {
                return;
            }
            
            cancelled = true;
            cancelTask();
            
            if (WIP.getAndIncrement(this) == 0) {
                s.cancel();
                queue.clear();
            }
        }
        
        void cancelTask() {
            IndexedCancellable a = task;
            if (a != TERMINATED) {
                a = TASK.getAndSet(this, TERMINATED);
                if (a != null && a != TERMINATED) {
                    a.cancel();
                }
            }
        }
        
        void trySchedule() {
            if (WIP.getAndIncrement(this) != 0) {
                return;
            }
            long idx = index++;
            
            Runnable f = scheduler.apply(this);
            
            RunnableIndexedCancellable next = null;
            for (;;) {
                IndexedCancellable curr = task;
                if (curr == TERMINATED) {
                    f.run();
                    return;
                }
                if (curr != null && curr.index() > idx) {
                    return;
                }
                if (next == null) {
                    next = new RunnableIndexedCancellable(idx, f);
                }
                if (TASK.compareAndSet(this, curr, next)) {
                    return;
                }
            }
        }
        
        @Override
        public void run() {
            int missed = 1;
            
            final Subscriber<? super T> a = actual;
            final Queue<T> q = queue;
            
            for (;;) {
                
                long r = requested;
                long e = 0;
                
                while (e != r) {
                    boolean d = done;
                    T v = q.poll();
                    boolean empty = v == null;
                    
                    if (checkTerminated(d, empty, a)) {
                        return;
                    }
                    
                    if (empty) {
                        break;
                    }
                    
                    a.onNext(v);
                    
                    e++;
                }
                
                if (e == r) {
                    if (checkTerminated(done, q.isEmpty(), a)) {
                        return;
                    }
                }
                
                if (e != 0L) {
                    if (sourceMode != SYNC) {
                        s.request(e);
                    }
                    if (r != Long.MAX_VALUE) {
                        REQUESTED.addAndGet(this, -e);
                    }
                }
                
                missed = WIP.addAndGet(this, -missed);
                if (missed == 0) {
                    break;
                }
            }
        }
        
        boolean checkTerminated(boolean d, boolean empty, Subscriber<?> a) {
            if (cancelled) {
                s.cancel();
                queue.clear();
                return true;
            }
            if (d) {
                if (delayError) {
                    if (empty) {
                        Throwable e = error;
                        if (e != null) {
                            a.onError(e);
                        } else {
                            a.onComplete();
                        }
                        return true;
                    }
                } else {
                    Throwable e = error;
                    if (e != null) {
                        queue.clear();
                        a.onError(e);
                        return true;
                    } else 
                    if (empty) {
                        a.onComplete();
                        return true;
                    }
                }
            }
            
            return false;
        }
    }
    
    interface IndexedCancellable {
        long index();
        
        void cancel();
    }
    
    static final class TerminatedIndexedCancellable implements IndexedCancellable {
        @Override
        public long index() {
            return Long.MAX_VALUE;
        }

        @Override
        public void cancel() {
            
        }
        
    }
    
    static final class RunnableIndexedCancellable implements IndexedCancellable {
        final long index;
        final Runnable run;
        public RunnableIndexedCancellable(long index, Runnable run) {
            this.index = index;
            this.run = run;
        }
        
        @Override
        public long index() {
            return index;
        }
        
        @Override
        public void cancel() {
            run.run();
        }
    }

    static class ExecutorServiceWorker implements Function<Runnable, Runnable> {

        final ExecutorService executor;

        public ExecutorServiceWorker(ExecutorService executor) {
            this.executor = executor;
        }

        @Override
        public Runnable apply(Runnable runnable) {
            final Future<?> f = executor.submit(runnable);
            return new CancelTask(f);
        }

        static class CancelTask implements Runnable {

            private final Future<?> f;

            public CancelTask(Future<?> f) {
                this.f = f;
            }

            @Override
            public void run() {
                f.cancel(true);
            }
        }
    }
}
