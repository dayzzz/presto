/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.hive;

import com.facebook.presto.hive.util.HiveFileIterator;
import com.facebook.presto.hive.util.ResumableTask;
import com.facebook.presto.hive.util.ResumableTasks;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.HostAddress;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.StandardErrorCode;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.google.common.collect.ImmutableList;
import io.airlift.units.DataSize;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.metastore.MetaStoreUtils;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.ql.io.SymlinkTextInputFormat;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.facebook.presto.hadoop.HadoopFileStatus.isDirectory;
import static com.facebook.presto.hive.HiveBucketing.HiveBucket;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_INVALID_METADATA;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_INVALID_PARTITION_VALUE;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_PARTITION_SCHEMA_MISMATCH;
import static com.facebook.presto.hive.HiveUtil.checkCondition;
import static com.facebook.presto.hive.HiveUtil.getInputFormat;
import static com.facebook.presto.hive.HiveUtil.isSplittable;
import static com.facebook.presto.hive.UnpartitionedPartition.isUnpartitioned;
import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.String.format;

public class BackgroundHiveSplitLoader
        implements HiveSplitLoader
{
    public static final CompletableFuture<?> COMPLETED_FUTURE = CompletableFuture.completedFuture(null);

    private final String connectorId;
    private final Table table;
    private final Optional<HiveBucketHandle> bucketHandle;
    private final Optional<HiveBucket> bucket;
    private final HdfsEnvironment hdfsEnvironment;
    private final NamenodeStats namenodeStats;
    private final DirectoryLister directoryLister;
    private final DataSize maxSplitSize;
    private final int maxPartitionBatchSize;
    private final DataSize maxInitialSplitSize;
    private final boolean recursiveDirWalkerEnabled;
    private final Executor executor;
    private final ConnectorSession session;
    private final ConcurrentLazyQueue<HivePartitionMetadata> partitions;
    private final Deque<HiveFileIterator> fileIterators = new ConcurrentLinkedDeque<>();
    private final AtomicInteger remainingInitialSplits;

    // Purpose of this lock:
    // * When write lock is acquired, except the holder, no one can do any of the following:
    // ** poll from partitions
    // ** poll from or push to fileIterators
    // ** push to hiveSplitSource
    // * When any of the above three operations is carried out, either a read lock or a write lock must be held.
    // * When a series of operations involving two or more of the above three operations are carried out, the lock
    //   must be continuously held throughout the series of operations.
    // Implications:
    // * if you hold a read lock but not a write lock, you can do any of the above three operations, but you may
    //   see a series of operations involving two or more of the operations carried out half way.
    private final ReentrantReadWriteLock taskExecutionLock = new ReentrantReadWriteLock();

    private HiveSplitSource hiveSplitSource;
    private volatile boolean stopped;

    public BackgroundHiveSplitLoader(
            String connectorId,
            Table table,
            Iterable<HivePartitionMetadata> partitions,
            Optional<HiveBucketHandle> bucketHandle,
            Optional<HiveBucket> bucket,
            DataSize maxSplitSize,
            ConnectorSession session,
            HdfsEnvironment hdfsEnvironment,
            NamenodeStats namenodeStats,
            DirectoryLister directoryLister,
            Executor executor,
            int maxPartitionBatchSize,
            DataSize maxInitialSplitSize,
            int maxInitialSplits,
            boolean recursiveDirWalkerEnabled)
    {
        this.connectorId = connectorId;
        this.table = table;
        this.bucketHandle = bucketHandle;
        this.bucket = bucket;
        this.maxSplitSize = maxSplitSize;
        this.maxPartitionBatchSize = maxPartitionBatchSize;
        this.session = session;
        this.hdfsEnvironment = hdfsEnvironment;
        this.namenodeStats = namenodeStats;
        this.directoryLister = directoryLister;
        this.maxInitialSplitSize = maxInitialSplitSize;
        this.remainingInitialSplits = new AtomicInteger(maxInitialSplits);
        this.recursiveDirWalkerEnabled = recursiveDirWalkerEnabled;
        this.executor = executor;
        this.partitions = new ConcurrentLazyQueue<>(partitions);
    }

    @Override
    public void start(HiveSplitSource splitSource)
    {
        this.hiveSplitSource = splitSource;
        for (int i = 0; i < maxPartitionBatchSize; i++) {
            ResumableTasks.submit(executor, new HiveSplitLoaderTask());
        }
    }

    @Override
    public void stop()
    {
        stopped = true;
    }

    private class HiveSplitLoaderTask
            implements ResumableTask
    {
        @Override
        public TaskStatus process()
        {
            while (true) {
                if (stopped) {
                    return TaskStatus.finished();
                }
                try {
                    CompletableFuture<?> future;
                    taskExecutionLock.readLock().lock();
                    try {
                        future = loadSplits();
                    }
                    finally {
                        taskExecutionLock.readLock().unlock();
                    }
                    invokeFinishedIfNecessary();
                    if (!future.isDone()) {
                        return TaskStatus.continueOn(future);
                    }
                }
                catch (Exception e) {
                    hiveSplitSource.fail(e);
                }
            }
        }
    }

    private void invokeFinishedIfNecessary()
    {
        if (partitions.isEmpty() && fileIterators.isEmpty()) {
            taskExecutionLock.writeLock().lock();
            try {
                // the write lock guarantees that no one is operating on the partitions, fileIterators, or hiveSplitSource, or half way through doing so.
                if (partitions.isEmpty() && fileIterators.isEmpty()) {
                    // It is legal to call `finished` multiple times or after `stop` was called.
                    // Nothing bad will happen if `finished` implementation calls methods that will try to obtain a read lock because the lock is re-entrant.
                    hiveSplitSource.finished();
                }
            }
            finally {
                taskExecutionLock.writeLock().unlock();
            }
        }
    }

    private CompletableFuture<?> loadSplits()
            throws IOException
    {
        HiveFileIterator files = fileIterators.poll();
        if (files == null) {
            HivePartitionMetadata partition = partitions.poll();
            if (partition == null) {
                return COMPLETED_FUTURE;
            }
            loadPartition(partition);
            return COMPLETED_FUTURE;
        }

        while (files.hasNext() && !stopped) {
            LocatedFileStatus file = files.next();
            if (isDirectory(file)) {
                if (recursiveDirWalkerEnabled) {
                    HiveFileIterator fileIterator = new HiveFileIterator(
                            file.getPath(),
                            files.getFileSystem(),
                            files.getDirectoryLister(),
                            files.getNamenodeStats(),
                            files.getPartitionName(),
                            files.getInputFormat(),
                            files.getSchema(),
                            files.getPartitionKeys(),
                            files.getEffectivePredicate());
                    fileIterators.add(fileIterator);
                }
            }
            else {
                boolean splittable = isSplittable(files.getInputFormat(), hdfsEnvironment.getFileSystem(file.getPath()), file.getPath());

                CompletableFuture<?> future = hiveSplitSource.addToQueue(createHiveSplits(
                        files.getPartitionName(),
                        file.getPath().toString(),
                        file.getBlockLocations(),
                        0,
                        file.getLen(),
                        files.getSchema(),
                        files.getPartitionKeys(),
                        splittable,
                        session,
                        OptionalInt.empty(),
                        files.getEffectivePredicate()));
                if (!future.isDone()) {
                    fileIterators.addFirst(files);
                    return future;
                }
            }
        }

        // No need to put the iterator back, since it's either empty or we've stopped
        return COMPLETED_FUTURE;
    }

    private void loadPartition(HivePartitionMetadata partition)
            throws IOException
    {
        String partitionName = partition.getHivePartition().getPartitionId();
        Properties schema = getPartitionSchema(table, partition.getPartition());
        List<HivePartitionKey> partitionKeys = getPartitionKeys(table, partition.getPartition());
        TupleDomain<HiveColumnHandle> effectivePredicate = partition.getHivePartition().getEffectivePredicate();

        Path path = new Path(getPartitionLocation(table, partition.getPartition()));
        Configuration configuration = hdfsEnvironment.getConfiguration(path);
        InputFormat<?, ?> inputFormat = getInputFormat(configuration, schema, false);

        if (inputFormat instanceof SymlinkTextInputFormat) {
            if (bucketHandle.isPresent()) {
                throw new PrestoException(StandardErrorCode.NOT_SUPPORTED, "Bucketed table in SymlinkTextInputFormat is not yet supported");
            }
            JobConf jobConf = new JobConf(configuration);
            FileInputFormat.setInputPaths(jobConf, path);
            InputSplit[] splits = inputFormat.getSplits(jobConf, 0);

            // TODO: This should use an iterator like the HiveFileIterator
            for (InputSplit rawSplit : splits) {
                FileSplit split = ((SymlinkTextInputFormat.SymlinkTextInputSplit) rawSplit).getTargetSplit();

                // get the filesystem for the target path -- it may be a different hdfs instance
                FileSystem targetFilesystem = hdfsEnvironment.getFileSystem(split.getPath());
                FileStatus file = targetFilesystem.getFileStatus(split.getPath());
                hiveSplitSource.addToQueue(createHiveSplits(
                        partitionName,
                        file.getPath().toString(),
                        targetFilesystem.getFileBlockLocations(file, split.getStart(), split.getLength()),
                        split.getStart(),
                        split.getLength(),
                        schema,
                        partitionKeys,
                        false,
                        session,
                        OptionalInt.empty(),
                        effectivePredicate));
                if (stopped) {
                    return;
                }
            }
            return;
        }

        // If only one bucket could match: load that one file
        FileSystem fs = hdfsEnvironment.getFileSystem(path);
        HiveFileIterator iterator = new HiveFileIterator(path, fs, directoryLister, namenodeStats, partitionName, inputFormat, schema, partitionKeys, effectivePredicate);
        if (bucket.isPresent()) {
            List<LocatedFileStatus> locatedFileStatuses = listAndSortBucketFiles(iterator, bucket.get().getBucketCount());
            FileStatus file = locatedFileStatuses.get(bucket.get().getBucketNumber());
            BlockLocation[] blockLocations = fs.getFileBlockLocations(file, 0, file.getLen());
            boolean splittable = isSplittable(inputFormat, fs, file.getPath());

            hiveSplitSource.addToQueue(createHiveSplits(
                    partitionName,
                    file.getPath().toString(),
                    blockLocations,
                    0,
                    file.getLen(),
                    schema,
                    partitionKeys,
                    splittable,
                    session,
                    OptionalInt.of(bucket.get().getBucketNumber()),
                    effectivePredicate));
            return;
        }

        // If table is bucketed: list the directory, sort, tag with bucket id
        if (bucketHandle.isPresent()) {
            // HiveFileIterator skips hidden files automatically.
            int bucketCount = bucketHandle.get().getBucketCount();
            List<LocatedFileStatus> list = listAndSortBucketFiles(iterator, bucketCount);

            for (int bucketIndex = 0; bucketIndex < bucketCount; bucketIndex++) {
                LocatedFileStatus file = list.get(bucketIndex);
                boolean splittable = isSplittable(iterator.getInputFormat(), hdfsEnvironment.getFileSystem(file.getPath()), file.getPath());

                hiveSplitSource.addToQueue(createHiveSplits(
                        iterator.getPartitionName(),
                        file.getPath().toString(),
                        file.getBlockLocations(),
                        0,
                        file.getLen(),
                        iterator.getSchema(),
                        iterator.getPartitionKeys(),
                        splittable,
                        session,
                        OptionalInt.of(bucketIndex),
                        iterator.getEffectivePredicate()));
            }

            return;
        }

        fileIterators.addLast(iterator);
    }

    private static List<LocatedFileStatus> listAndSortBucketFiles(HiveFileIterator hiveFileIterator, int bucketCount)
    {
        ArrayList<LocatedFileStatus> list = new ArrayList<>(bucketCount);

        while (hiveFileIterator.hasNext()) {
            LocatedFileStatus next = hiveFileIterator.next();
            if (isDirectory(next)) {
                // Fail here to be on the safe side. This seems to be the same as what Hive does
                throw new PrestoException(HIVE_PARTITION_SCHEMA_MISMATCH, "Found sub-directory in bucket directory");
            }
            if (list.size() >= bucketCount) {
                throw new PrestoException(HIVE_PARTITION_SCHEMA_MISMATCH, "There are more files in the directory than the specified bucket count: " + bucketCount);
            }
            list.add(next);
        }

        if (list.size() != bucketCount) {
            throw new PrestoException(HIVE_PARTITION_SCHEMA_MISMATCH, "There are less files in the directory than the specified bucket count: " + bucketCount);
        }

        // Sort FileStatus objects (instead of, e.g., fileStatus.getPath().toString). This matches org.apache.hadoop.hive.ql.metadata.Table.getSortedPaths
        list.sort(null);
        return list;
    }

    private List<HiveSplit> createHiveSplits(
            String partitionName,
            String path,
            BlockLocation[] blockLocations,
            long start,
            long length,
            Properties schema,
            List<HivePartitionKey> partitionKeys,
            boolean splittable,
            ConnectorSession session,
            OptionalInt bucketNumber,
            TupleDomain<HiveColumnHandle> effectivePredicate)
            throws IOException
    {
        ImmutableList.Builder<HiveSplit> builder = ImmutableList.builder();

        boolean forceLocalScheduling = HiveSessionProperties.isForceLocalScheduling(session);

        if (splittable) {
            for (BlockLocation blockLocation : blockLocations) {
                // get the addresses for the block
                List<HostAddress> addresses = toHostAddress(blockLocation.getHosts());

                long maxBytes = maxSplitSize.toBytes();
                boolean creatingInitialSplits = false;

                if (remainingInitialSplits.get() > 0) {
                    maxBytes = maxInitialSplitSize.toBytes();
                    creatingInitialSplits = true;
                }

                // divide the block into uniform chunks that are smaller than the max split size
                int chunks = Math.max(1, (int) (blockLocation.getLength() / maxBytes));
                // when block does not divide evenly into chunks, make the chunk size slightly bigger than necessary
                long targetChunkSize = (long) Math.ceil(blockLocation.getLength() * 1.0 / chunks);

                long chunkOffset = 0;
                while (chunkOffset < blockLocation.getLength()) {
                    if (remainingInitialSplits.decrementAndGet() < 0 && creatingInitialSplits) {
                        creatingInitialSplits = false;
                        // recalculate the target chunk size
                        maxBytes = maxSplitSize.toBytes();
                        long remainingLength = blockLocation.getLength() - chunkOffset;
                        chunks = Math.max(1, (int) (remainingLength / maxBytes));
                        targetChunkSize = (long) Math.ceil(remainingLength * 1.0 / chunks);
                    }
                    // adjust the actual chunk size to account for the overrun when chunks are slightly bigger than necessary (see above)
                    long chunkLength = Math.min(targetChunkSize, blockLocation.getLength() - chunkOffset);

                    builder.add(new HiveSplit(connectorId,
                            table.getDbName(),
                            table.getTableName(),
                            partitionName,
                            path,
                            blockLocation.getOffset() + chunkOffset,
                            chunkLength,
                            schema,
                            partitionKeys,
                            addresses,
                            bucketNumber,
                            forceLocalScheduling,
                            effectivePredicate));

                    chunkOffset += chunkLength;
                }
                checkState(chunkOffset == blockLocation.getLength(), "Error splitting blocks");
            }
        }
        else {
            // not splittable, use the hosts from the first block if it exists
            List<HostAddress> addresses = ImmutableList.of();
            if (blockLocations.length > 0) {
                addresses = toHostAddress(blockLocations[0].getHosts());
            }

            builder.add(new HiveSplit(connectorId,
                    table.getDbName(),
                    table.getTableName(),
                    partitionName,
                    path,
                    start,
                    length,
                    schema,
                    partitionKeys,
                    addresses,
                    bucketNumber,
                    forceLocalScheduling,
                    effectivePredicate));
        }
        return builder.build();
    }

    private static List<HostAddress> toHostAddress(String[] hosts)
    {
        ImmutableList.Builder<HostAddress> builder = ImmutableList.builder();
        for (String host : hosts) {
            builder.add(HostAddress.fromString(host));
        }
        return builder.build();
    }

    private static List<HivePartitionKey> getPartitionKeys(Table table, Partition partition)
    {
        if (isUnpartitioned(partition)) {
            return ImmutableList.of();
        }
        ImmutableList.Builder<HivePartitionKey> partitionKeys = ImmutableList.builder();
        List<FieldSchema> keys = table.getPartitionKeys();
        List<String> values = partition.getValues();
        checkCondition(keys.size() == values.size(), HIVE_INVALID_METADATA, "Expected %s partition key values, but got %s", keys.size(), values.size());
        for (int i = 0; i < keys.size(); i++) {
            String name = keys.get(i).getName();
            HiveType hiveType = HiveType.valueOf(keys.get(i).getType());
            if (!hiveType.isSupportedType()) {
                throw new PrestoException(NOT_SUPPORTED, format("Unsupported Hive type %s found in partition keys of table %s.%s", hiveType, table.getDbName(), table.getTableName()));
            }
            String value = values.get(i);
            checkCondition(value != null, HIVE_INVALID_PARTITION_VALUE, "partition key value cannot be null for field: %s", name);
            partitionKeys.add(new HivePartitionKey(name, hiveType, value));
        }
        return partitionKeys.build();
    }

    private static Properties getPartitionSchema(Table table, Partition partition)
    {
        if (isUnpartitioned(partition)) {
            return MetaStoreUtils.getTableMetadata(table);
        }
        return MetaStoreUtils.getSchema(partition, table);
    }

    private static String getPartitionLocation(Table table, Partition partition)
    {
        if (isUnpartitioned(partition)) {
            return table.getSd().getLocation();
        }
        return partition.getSd().getLocation();
    }
}
