/*
 * Copyright 2011-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.collector;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.collect.Sets;
import com.google.common.io.CharSource;
import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Clock;
import org.glowroot.config.ConfigService;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.UsedByReflection;
import org.glowroot.transaction.TransactionCollector;
import org.glowroot.transaction.model.Transaction;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class TransactionCollectorImpl implements TransactionCollector {

    private static final Logger logger = LoggerFactory.getLogger(TransactionCollectorImpl.class);

    private static final int PENDING_LIMIT = 100;

    // this is only used for benchmarking overhead of trace storage
    @OnlyUsedByTests
    @UsedByReflection
    private static boolean useSynchronousStore;

    private final ExecutorService executorService;
    private final ConfigService configService;
    private final TraceRepository traceRepository;
    private final @Nullable AggregateCollector aggregateCollector;
    private final Clock clock;
    private final Ticker ticker;
    private final Set<Transaction> pendingTransactions = Sets.newCopyOnWriteArraySet();

    private final RateLimiter warningRateLimiter = RateLimiter.create(1.0 / 60);
    @GuardedBy("warningLock")
    private int countSinceLastWarning;

    TransactionCollectorImpl(ExecutorService executorService, ConfigService configService,
            TraceRepository traceRepository, @Nullable AggregateCollector aggregateCollector,
            Clock clock, Ticker ticker) {
        this.executorService = executorService;
        this.configService = configService;
        this.traceRepository = traceRepository;
        this.aggregateCollector = aggregateCollector;
        this.clock = clock;
        this.ticker = ticker;
    }

    public boolean shouldStore(Transaction transaction) {
        if (transaction.isPartiallyStored() || transaction.getErrorMessage() != null) {
            return true;
        }
        // check if should store for user recording
        if (configService.getUserRecordingConfig().enabled()) {
            String user = transaction.getUser();
            if (!Strings.isNullOrEmpty(user)
                    && user.equalsIgnoreCase(configService.getUserRecordingConfig().user())) {
                return true;
            }
        }
        // check if trace-specific store threshold was set
        long traceStoreThresholdMillis = transaction.getTraceStoreThresholdMillisOverride();
        if (traceStoreThresholdMillis != Transaction.USE_GENERAL_STORE_THRESHOLD) {
            return transaction.getDuration() >= MILLISECONDS.toNanos(traceStoreThresholdMillis);
        }
        // fall back to default trace store threshold
        traceStoreThresholdMillis = configService.getGeneralConfig().traceStoreThresholdMillis();
        return transaction.getDuration() >= MILLISECONDS.toNanos(traceStoreThresholdMillis);
    }

    public Collection<Transaction> getPendingTransactions() {
        return pendingTransactions;
    }

    @Override
    public void onCompletedTransaction(final Transaction transaction) {
        // capture time is calculated by the aggregator because it depends on monotonically
        // increasing capture times so it can flush aggregates without concern for new data
        // arriving with a prior capture time
        //
        // this is a reasonable place to get the capture time since this code is still being
        // executed by the transaction thread
        boolean store = shouldStore(transaction);
        final long captureTime;
        if (aggregateCollector == null) {
            captureTime = clock.currentTimeMillis();
        } else {
            if (store) {
                transaction.setWillBeStored();
            }
            transaction.onCompleteCaptureThreadInfo();
            captureTime = aggregateCollector.add(transaction);
        }
        if (store) {
            if (aggregateCollector == null) {
                // wasn't captured above but is needed
                transaction.onCompleteCaptureThreadInfo();
            }
            transaction.onCompleteCaptureGcInfo();
            transaction.onComplete(captureTime);
            if (pendingTransactions.size() < PENDING_LIMIT) {
                pendingTransactions.add(transaction);
            } else {
                logPendingLimitWarning();
                store = false;
            }
            Runnable command = new Runnable() {
                @Override
                public void run() {
                    try {
                        Trace trace = TraceCreator.createCompletedTrace(transaction);
                        store(trace, transaction);
                    } catch (Throwable t) {
                        logger.error(t.getMessage(), t);
                    } finally {
                        pendingTransactions.remove(transaction);
                    }
                }
            };
            if (useSynchronousStore) {
                command.run();
            } else {
                executorService.execute(command);
            }
        }
    }

    // no need to throttle partial trace storage since throttling is handled upstream by using a
    // single thread executor in PartialTraceStorageWatcher
    @Override
    public void storePartialTrace(Transaction transaction) {
        try {
            Trace trace = TraceCreator.createPartialTrace(transaction, clock.currentTimeMillis(),
                    ticker.read());
            transaction.setPartiallyStored();
            if (!transaction.isCompleted()) {
                store(trace, transaction);
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    private void logPendingLimitWarning() {
        synchronized (warningRateLimiter) {
            if (warningRateLimiter.tryAcquire(0, MILLISECONDS)) {
                logger.warn("not storing a trace because of an excessive backlog of {} traces"
                        + " already waiting to be stored (this warning will appear at most once a"
                        + " minute, there were {} additional traces not stored since the last"
                        + " warning)", PENDING_LIMIT, countSinceLastWarning);
                countSinceLastWarning = 0;
            } else {
                countSinceLastWarning++;
            }
        }
    }

    private void store(Trace trace, Transaction transaction) throws Exception {
        long captureTick;
        if (transaction.isCompleted()) {
            captureTick = transaction.getEndTick();
        } else {
            captureTick = ticker.read();
        }
        CharSource entries = EntriesCharSourceCreator.createEntriesCharSource(
                transaction.getEntriesCopy(), transaction.getStartTick(), captureTick);
        CharSource profile =
                ProfileCharSourceCreator.createProfileCharSource(transaction.getProfile());
        traceRepository.store(trace, entries, profile);
    }
}
