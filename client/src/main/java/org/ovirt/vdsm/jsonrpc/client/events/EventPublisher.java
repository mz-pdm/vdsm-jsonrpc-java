package org.ovirt.vdsm.jsonrpc.client.events;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.ovirt.vdsm.jsonrpc.client.ClientConnectionException;
import org.ovirt.vdsm.jsonrpc.client.EventDecomposer;
import org.ovirt.vdsm.jsonrpc.client.JsonRpcEvent;
import org.ovirt.vdsm.jsonrpc.client.internal.ResponseWorker;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jsonrpc implementation of {@link Publisher}
 *
 */
public class EventPublisher implements Publisher<Map<String, Object>, EventSubscriber> {

    private static Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1);
    private SubscriptionMatcher matcher;
    private EventDecomposer decomposer;
    private int eventTimeoutInHours;

    public EventPublisher(ExecutorService executorService, int eventTimeoutInHours) {
        this.executorService = executorService;
        this.matcher = new SubscriptionMatcher();
        this.decomposer = new EventDecomposer();
        this.eventTimeoutInHours = eventTimeoutInHours;
        scheduleCleanupTask();
    }

    private void scheduleCleanupTask() {
        try {
            scheduledExecutorService.scheduleWithFixedDelay(() -> cleanupOldEvents(),
                    eventTimeoutInHours,
                    eventTimeoutInHours,
                    TimeUnit.HOURS);
        } catch (Throwable t) {
            log.error("Unable to schedule cleanup task : {}", ExceptionUtils.getRootCauseMessage(t));
            log.debug("Exception", t);
        }
    }

    public void cleanupOldEvents() {
        try {
            for (SubscriptionHolder holder : matcher.getAllSubscriptions()) {
                holder.purgeOldEventsIfNotConsumed(eventTimeoutInHours);
            }
        } catch (Throwable t) {
            log.error("Error purging old events from SubscriptionHolder : ", ExceptionUtils.getRootCauseMessage(t));
            log.debug("Exception", t);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.reactivestreams.Publisher#subscribe(org.reactivestreams.Subscriber)
     */
    @Override
    public void subscribe(final EventSubscriber subscriber) {
        final AtomicInteger count = new AtomicInteger();
        final SubscriptionHolder holder = new SubscriptionHolder(subscriber, count);
        Subscription subscription = new Subscription() {

            @Override
            public void request(int n) {
                count.addAndGet(n);
                process(holder);
            }

            @Override
            public void cancel() {
                clean(holder);
                subscriber.onComplete();
            }
        };
        subscriber.onSubscribe(subscription);
        this.matcher.add(holder);
    }

    @Override
    public void publish(final String subscriptionId, final Map<String, Object> params) throws IOException {
        process(JsonRpcEvent.fromMethodAndParams(subscriptionId, params));
    }

    private void process(SubscriptionHolder holder) {
        this.executorService.submit(new EventCallable(holder, this.decomposer));
    }

    private void clean(SubscriptionHolder holder) {
        this.matcher.remove(holder);
        holder.clean();
    }

    /**
     * This method is used by @link {@link ResponseWorker} to submit an @link {@link JsonRpcEvent} for processing.
     *
     * @param event
     *            which is submitted for processing.
     */
    public void process(JsonRpcEvent event) {
        Set<SubscriptionHolder> holders = matcher.match(event);
        holders.stream()
                .peek(holder -> holder.putEvent(event))
                .filter(holder -> holder.canProcess())
                .forEach(holder -> this.executorService.submit(new EventCallable(holder, this.decomposer)));
    }

    /*
     * (non-Javadoc)
     *
     * Used by test case to count number of events
     */
    public int countEvents(JsonRpcEvent event) {
        Set<SubscriptionHolder> holders = matcher.match(event);
        return holders.stream()
                .mapToInt(holder -> holder.getNumberOfEvents())
                .sum();
    }

    /**
     * Event processing task which is submit to a {@link ExecutorService} for processing.
     *
     */
    class EventCallable implements Callable<Void> {

        private SubscriptionHolder holder;
        private EventDecomposer decomposer;

        /**
         * @param holder
         *            Holds subscription information.
         * @param decomposer
         *            is used for decomposing event before notifying @link {@link EventSubscriber}.
         */
        public EventCallable(SubscriptionHolder holder, EventDecomposer decomposer) {
            this.holder = holder;
            this.decomposer = decomposer;
        }

        @Override
        public Void call() throws Exception {
            Subscriber<Map<String, Object>> subscriber = this.holder.getSubscriber();
            JsonRpcEvent event;
            while ((event = this.holder.canProcessMore()) != null) {
                handleEvent(subscriber, event);
            }
            return null;
        }

        private void handleEvent(Subscriber<Map<String, Object>> subscriber, JsonRpcEvent event) {
            try {
                Map<String, Object> map = this.decomposer.decompose(event);
                if (map.containsKey(JsonRpcEvent.ERROR_KEY)) {
                    subscriber.onError(new ClientConnectionException((String) map.get(JsonRpcEvent.ERROR_KEY)));
                } else {
                    subscriber.onNext(map);
                }
            } catch (Throwable t) {
                log.error("Error processing event '{}' for subscriber '{}' : {}.",
                        event.toString(),
                        subscriber.getClass().getCanonicalName(),
                        ExceptionUtils.getRootCauseMessage(t));
                log.debug("Exception", t);
            }
        }
    }

    public void close() {
        this.executorService.shutdown();
    }
}
