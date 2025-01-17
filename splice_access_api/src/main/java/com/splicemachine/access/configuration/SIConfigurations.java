/*
 * Copyright (c) 2012 - 2020 Splice Machine, Inc.
 *
 * This file is part of Splice Machine.
 * Splice Machine is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3, or (at your option) any later version.
 * Splice Machine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License along with Splice Machine.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package com.splicemachine.access.configuration;

import com.splicemachine.access.api.Durability;
import com.splicemachine.primitives.Bytes;
import org.apache.log4j.Logger;

/**
 * Repository for holding configuration keys for SI.
 * <p/>
 * Each specific architecture configuration should provide
 * a default value for each of these keys.
 *
 * @author Scott Fines
 *         Date: 12/15/15
 */
public class SIConfigurations implements ConfigurationDefault {
    private static final Logger LOG = Logger.getLogger(SIConfigurations.class);

    public static final String completedTxnCacheSize="splice.txn.completedTxns.cacheSize";
    private static final int DEFAULT_COMPLETED_TRANSACTION_CACHE_SIZE=1<<20; // want to hold lots of completed transactions

    public static final String completedTxnConcurrency="splice.txn.completedTxns.concurrency";
    private static final int DEFAULT_COMPLETED_TRANSACTION_CONCURRENCY=64;

    public static final String TRANSACTION_KEEP_ALIVE_INTERVAL="splice.txn.keepAliveIntervalMs";
    public static final long DEFAULT_TRANSACTION_KEEP_ALIVE_INTERVAL=15000L;

    public static final String TRANSACTION_TIMEOUT="splice.txn.timeout";
    public static final long DEFAULT_TRANSACTION_TIMEOUT=10*DEFAULT_TRANSACTION_KEEP_ALIVE_INTERVAL; // 2.5 Minutes

    public static final String TRANSACTION_KEEP_ALIVE_THREADS="splice.txn.keepAliveThreads";
    public static final int DEFAULT_KEEP_ALIVE_THREADS=4;

    public static final String READ_RESOLVER_THREADS = "splice.txn.readresolver.threads";
    private static final int DEFAULT_READ_RESOLVER_THREADS = 4;

    public static final String READ_RESOLVER_QUEUE_SIZE = "splice.txn.readresolver.queueSize";
    private static final int DEFAULT_READ_RESOLVER_QUEUE_SIZE=1<<16;

    public static final String IGNORE_MISSING_TXN = "splice.ignore.missing.transactions";
    private static final boolean DEFAULT_IGNORE_MISSING_TXN=false;

    /**
     * The default minimum retention period for system tables in seconds.
     */
    public static final String SYS_TABLES_RETENTION_PERIOD = "splice.sys.tables.retention.period";
    private static final long DEFAULT_SYS_TABLES_RETENTION_PERIOD=7*24*3600;

    /*
     * We use lock-striping to manage concurrent modifications/reads to the Transaction table. That is,
     * each Transaction is grouped into a bucket, and in order to read or modify that transaction, you must
     * first acquire the lock for that bucket.
     *
     * As a general rule, the more stripes you have, the more parallelism you can sustain. However, there are
     * two limiting factors to this. The first is memory--each stripe requires a separate set of objects which
     * occupy heap space. The second is threading performance.
     *
     * By experimentation, I've determined that (on the Oracle 6 JVM, at least) a ReadWriteLock occupies ~250 bytes,
     * so the total memory occupied is ~250*STRIPES, where STRIPES is the number of stripes that we have. Thus,
     * we have a table of memory usage as follows:
     *
     * 16       --  ~4K
     * 32       --  ~8K
     * 64       --  ~16K
     * 128      --  ~32K
     * 256      --  ~64K
     * 512      --  ~125K
     * 1024     --  ~250K
     * 4096     --  ~1M
     * 8192     --  ~2M
     * 16384    --  ~4M
     * 32768    --  ~8M
     *
     * This is the size for each transaction region, so there are actually 16 times that number of stripes (
     * and thus 16 times the memory usage).
     *
     * This inclines us to choose fewer stripes. However, we want to sustain a high degree of concurrency,
     * so we want to choose the correct number of stripes. Thankfully, we have a total limiter.
     *
     * All access to the transaction table occurs remotely (through the HBase client API), which means
     * that our maximum concurrency is actually the number of concurrent network actions that can be made
     * to a single server--in other words, the IPC threads. Any concurrency level which is higher than that
     * will be useless concurrency, as only a maximum of that many threads will be used. Thus, a reasonable
     * default is the number of ipc threads configured for this system.
     *
     * Note that the Stripe count is always a power of 2(if you set it to a non-power of 2, then the striper
     * will choose the smallest power of 2 greater than what you set), so we will always have a concurrency level
     * which is >= the number of ipc threads, which should allow plenty of concurrency for our applications.
     *
     * However, if we see bottlenecks due to this lock striping, then we may increase it manually, given the
     * tradeoffs that we discuss in this note.
     *
     */
    public static final String TRANSACTION_LOCK_STRIPES ="splice.txn.concurrencyLevel";

    /**
     * The number of milliseconds the timestamp client should wait for the response.
     * Defaults to 60000 (60 seconds)
     */
    public static final String TIMESTAMP_CLIENT_WAIT_TIME = "splice.timestamp_server.clientWaitTime";
    private static final int DEFAULT_TIMESTAMP_CLIENT_WAIT_TIME = 60000;

    /**
     * The number of queues were new timestamp requests are batched up.
     * Defaults to 5
     */
    public static final String TIMESTAMP_CLIENT_QUEUES = "splice.timestamp_server.clientQueues";
    private static final int DEFAULT_TIMESTAMP_CLIENT_QUEUES = 5;

    /**
     * Whether new timestamp requests are batched up, sacrificing latency for more throughput.
     * Defaults to false
     */
    public static final String TIMESTAMP_CLIENT_BATCHED = "splice.timestamp_server.batched";
    private static final boolean DEFAULT_TIMESTAMP_CLIENT_BATCHED = false;

    /**
     * The Port to bind the Timestamp Server connection to
     * Defaults to 60012
     */
    public static final String TIMESTAMP_SERVER_BIND_PORT = "splice.timestamp_server.port";
    private static final int DEFAULT_TIMESTAMP_SERVER_BIND_PORT = 60012;

    public static final String ACTIVE_TRANSACTION_MAX_CACHE_SIZE="splice.txn.activeMaxCacheSize";
    public static final int DEFAULT_ACTIVE_TRANSACTION_MAX_CACHE_SIZE = 1<<12;

    public static final String ACTIVE_TRANSACTION_INITIAL_CACHE_SIZE="splice.txn.activeInitialCacheSize";
    public static final int DEFAULT_ACTIVE_TRANSACTION_INITIAL_CACHE_SIZE = 1<<7;

    // Share of time spent on transaction resolution during flushes, between 0 and 1 (no time vs infinite time)
    public static final String FLUSH_RESOLUTION_SHARE = "splice.txn.resolution.flush.share";
    public static final double DEFAULT_FLUSH_RESOLUTION_SHARE = 0.2f;

    // Size of buffer for HRegionServer async transaction resolution
    public static final String COMPACTION_RESOLUTION_BUFFER_SIZE = "splice.local.compaction.resolution.bufferSize";
    public static final int DEFAULT_COMPACTION_RESOLUTION_BUFFER_SIZE = 100;

    // Whether we resolve transactions on flushes
    public static final String RESOLUTION_ON_FLUSHES = "splice.txn.resolution.flushes";
    public static final boolean DEFAULT_RESOLUTION_ON_FLUSHES = true;

    public static final String ROLLFORWARD_QUEUE_SIZE = "splice.txn.rollforward.queueSize";
    public static final int DEFAULT_ROLLFORWARD_QUEUE_SIZE = 4096;

    // Wait time for first resolution attempt in ms
    public static final String ROLLFORWARD_FIRST_WAIT = "splice.txn.rollforward.firstQueueWait";
    public static final int DEFAULT_ROLLFORWARD_FIRST_WAIT = 1000;

    // Wait time for second resolution attempt in ms
    public static final String ROLLFORWARD_SECOND_WAIT = "splice.txn.rollforward.secondQueueWait";
    public static final int DEFAULT_ROLLFORWARD_SECOND_WAIT = 10000;

    // Threads processing first queue
    public static final String ROLLFORWARD_FIRST_THREADS = "splice.txn.rollforward.firstQueueThreads";
    public static final int DEFAULT_ROLLFORWARD_FIRST_THREADS = 25;

    // Threads processing second queue
    public static final String ROLLFORWARD_SECOND_THREADS = "splice.txn.rollforward.secondQueueThreads";
    public static final int DEFAULT_ROLLFORWARD_SECOND_THREADS = 1;

    // Durability setting, useful for testing or standalone
    // Options: NONE, ASYNC, SYNC
    public static final String DURABILITY = "splice.txn.durability";
    public static final String DEFAULT_DURABILITY = "SYNC";

    @Override
    public void setDefaults(ConfigurationBuilder builder, ConfigurationSource configurationSource) {
        builder.completedTxnConcurrency  = configurationSource.getInt(completedTxnConcurrency, DEFAULT_COMPLETED_TRANSACTION_CONCURRENCY);
        builder.completedTxnCacheSize  = configurationSource.getInt(completedTxnCacheSize, DEFAULT_COMPLETED_TRANSACTION_CACHE_SIZE);
        builder.transactionKeepAliveThreads  = configurationSource.getInt(TRANSACTION_KEEP_ALIVE_THREADS, DEFAULT_KEEP_ALIVE_THREADS);
        builder.readResolverThreads  = configurationSource.getInt(READ_RESOLVER_THREADS, DEFAULT_READ_RESOLVER_THREADS);
        builder.readResolverQueueSize  = configurationSource.getInt(READ_RESOLVER_QUEUE_SIZE, -1); //TODO -sf- reset to DEFAULT once ReadResolution works
//        builder.readResolverQueueSize  = configurationSource.getInt(READ_RESOLVER_QUEUE_SIZE, DEFAULT_READ_RESOLVER_QUEUE_SIZE);
        builder.timestampClientWaitTime  = configurationSource.getInt(TIMESTAMP_CLIENT_WAIT_TIME, DEFAULT_TIMESTAMP_CLIENT_WAIT_TIME);
        builder.timestampClientQueues = configurationSource.getInt(TIMESTAMP_CLIENT_QUEUES, DEFAULT_TIMESTAMP_CLIENT_QUEUES);
        builder.timestampClientBatched = configurationSource.getBoolean(TIMESTAMP_CLIENT_BATCHED, DEFAULT_TIMESTAMP_CLIENT_BATCHED);
        builder.timestampServerBindPort  = configurationSource.getInt(TIMESTAMP_SERVER_BIND_PORT, DEFAULT_TIMESTAMP_SERVER_BIND_PORT);
        builder.activeTransactionMaxCacheSize = configurationSource.getInt(ACTIVE_TRANSACTION_MAX_CACHE_SIZE, DEFAULT_ACTIVE_TRANSACTION_MAX_CACHE_SIZE);
        builder.activeTransactionInitialCacheSize = configurationSource.getInt(ACTIVE_TRANSACTION_INITIAL_CACHE_SIZE, DEFAULT_ACTIVE_TRANSACTION_INITIAL_CACHE_SIZE);

        builder.transactionTimeout = configurationSource.getLong(TRANSACTION_TIMEOUT, DEFAULT_TRANSACTION_TIMEOUT);
        builder.transactionKeepAliveInterval = configurationSource.getLong(TRANSACTION_KEEP_ALIVE_INTERVAL, DEFAULT_TRANSACTION_KEEP_ALIVE_INTERVAL);

        builder.ignoreMissingTxns = configurationSource.getBoolean(IGNORE_MISSING_TXN, DEFAULT_IGNORE_MISSING_TXN);

        builder.systablesMinRetentionPeriod = configurationSource.getLong(SYS_TABLES_RETENTION_PERIOD, DEFAULT_SYS_TABLES_RETENTION_PERIOD);

        builder.flushResolutionShare = configurationSource.getDouble(FLUSH_RESOLUTION_SHARE, DEFAULT_FLUSH_RESOLUTION_SHARE, 0, 1);

        builder.localCompactionResolutionBufferSize = configurationSource.getInt(COMPACTION_RESOLUTION_BUFFER_SIZE, DEFAULT_COMPACTION_RESOLUTION_BUFFER_SIZE);

        builder.resolutionOnFlushes = configurationSource.getBoolean(RESOLUTION_ON_FLUSHES, DEFAULT_RESOLUTION_ON_FLUSHES);

        builder.rollForwardQueueSize  = configurationSource.getInt(ROLLFORWARD_QUEUE_SIZE, DEFAULT_ROLLFORWARD_QUEUE_SIZE);
        builder.rollForwardFirstWait  = configurationSource.getInt(ROLLFORWARD_FIRST_WAIT, DEFAULT_ROLLFORWARD_FIRST_WAIT);
        builder.rollForwardSecondWait  = configurationSource.getInt(ROLLFORWARD_SECOND_WAIT, DEFAULT_ROLLFORWARD_SECOND_WAIT);
        builder.rollForwardFirstThreads  = configurationSource.getInt(ROLLFORWARD_FIRST_THREADS, DEFAULT_ROLLFORWARD_FIRST_THREADS);
        builder.rollForwardSecondThreads = configurationSource.getInt(ROLLFORWARD_SECOND_THREADS, DEFAULT_ROLLFORWARD_SECOND_THREADS);

        String durability = configurationSource.getString(DURABILITY, DEFAULT_DURABILITY);
        try {
            builder.durability = Durability.valueOf(durability);
        } catch (IllegalArgumentException ex) {
            LOG.error("Couldn't parse durability option: " + durability);
            builder.durability = Durability.SYNC;
        }
    }
}
