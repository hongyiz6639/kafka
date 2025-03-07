/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.processor.internals;

import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.TopologyException;
import org.apache.kafka.streams.errors.UnknownTopologyException;
import org.apache.kafka.streams.internals.StreamsConfigUtils;
import org.apache.kafka.streams.internals.StreamsConfigUtils.ProcessingMode;
import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.processor.TaskId;
import org.apache.kafka.streams.processor.internals.InternalTopologyBuilder.TopicsInfo;
import org.apache.kafka.streams.processor.internals.namedtopology.TopologyConfig.TaskConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Collections.emptySet;

public class TopologyMetadata {
    private Logger log;

    // the "__" (double underscore) string is not allowed for topology names, so it's safe to use to indicate
    // that it's not a named topology
    public static final String UNNAMED_TOPOLOGY = "__UNNAMED_TOPOLOGY__";
    private static final Pattern EMPTY_ZERO_LENGTH_PATTERN = Pattern.compile("");

    private final StreamsConfig config;
    private final ProcessingMode processingMode;
    private final TopologyVersion version;

    private final ConcurrentNavigableMap<String, InternalTopologyBuilder> builders; // Keep sorted by topology name for readability

    private ProcessorTopology globalTopology;
    private final Map<String, StateStore> globalStateStores = new HashMap<>();
    private final Set<String> allInputTopics = new HashSet<>();
    private final Map<String, Long> threadVersions = new ConcurrentHashMap<>();

    public static class TopologyVersion {
        public AtomicLong topologyVersion = new AtomicLong(0L); // the local topology version
        public ReentrantLock topologyLock = new ReentrantLock();
        public Condition topologyCV = topologyLock.newCondition();
        public List<TopologyVersionWaiters> activeTopologyWaiters = new LinkedList<>();
    }

    public static class TopologyVersionWaiters {
        final long topologyVersion; // the (minimum) version to wait for these threads to cross
        final KafkaFutureImpl<Void> future; // the future waiting on all threads to be updated

        public TopologyVersionWaiters(final long topologyVersion, final KafkaFutureImpl<Void> future) {
            this.topologyVersion = topologyVersion;
            this.future = future;
        }
    }

    public TopologyMetadata(final InternalTopologyBuilder builder,
                            final StreamsConfig config) {
        version = new TopologyVersion();
        processingMode = StreamsConfigUtils.processingMode(config);
        this.config = config;

        builders = new ConcurrentSkipListMap<>();
        if (builder.hasNamedTopology()) {
            builders.put(builder.topologyName(), builder);
        } else {
            builders.put(UNNAMED_TOPOLOGY, builder);
        }
    }

    public TopologyMetadata(final ConcurrentNavigableMap<String, InternalTopologyBuilder> builders,
                            final StreamsConfig config) {
        this.version = new TopologyVersion();
        this.processingMode = StreamsConfigUtils.processingMode(config);
        this.config = config;
        this.log = LoggerFactory.getLogger(getClass());


        this.builders = builders;
        if (builders.isEmpty()) {
            log.info("Created an empty KafkaStreams app with no topology");
        }
    }

    // Need to (re)set the log here to pick up the `processId` part of the clientId in the prefix
    public void setLog(final LogContext logContext) {
        log = logContext.logger(getClass());
    }
    
    public ProcessingMode processingMode() {
        return processingMode;
    }

    public long topologyVersion() {
        return version.topologyVersion.get();
    }

    private void lock() {
        version.topologyLock.lock();
    }

    private void unlock() {
        version.topologyLock.unlock();
    }

    public Collection<String> sourceTopicsForTopology(final String name) {
        return builders.get(name).fullSourceTopicNames();
    }

    public boolean needsUpdate(final String threadName) {
        return threadVersions.get(threadName) < topologyVersion();
    }

    public void registerThread(final String threadName) {
        threadVersions.put(threadName, 0L);
    }

    public void unregisterThread(final String threadName) {
        threadVersions.remove(threadName);
        maybeNotifyTopologyVersionWaitersAndUpdateThreadsTopologyVersion(threadName);
    }

    public void maybeNotifyTopologyVersionWaitersAndUpdateThreadsTopologyVersion(final String threadName) {
        try {
            lock();
            final Iterator<TopologyVersionWaiters> iterator = version.activeTopologyWaiters.listIterator();
            TopologyVersionWaiters topologyVersionWaiters;
            threadVersions.put(threadName, topologyVersion());
            while (iterator.hasNext()) {
                topologyVersionWaiters = iterator.next();
                final long topologyVersionWaitersVersion = topologyVersionWaiters.topologyVersion;
                if (topologyVersionWaitersVersion <= threadVersions.get(threadName)) {
                    if (threadVersions.values().stream().allMatch(t -> t >= topologyVersionWaitersVersion)) {
                        topologyVersionWaiters.future.complete(null);
                        iterator.remove();
                        log.info("All threads are now on topology version {}", topologyVersionWaiters.topologyVersion);
                    }
                }
            }
        } finally {
            unlock();
        }
    }

    public void wakeupThreads() {
        try {
            lock();
            version.topologyCV.signalAll();
        } finally {
            unlock();
        }
    }

    public void maybeWaitForNonEmptyTopology(final Supplier<StreamThread.State> threadState) {
        if (isEmpty() && threadState.get().isAlive()) {
            try {
                lock();
                while (isEmpty() && threadState.get().isAlive()) {
                    try {
                        log.debug("Detected that the topology is currently empty, waiting for something to process");
                        version.topologyCV.await();
                    } catch (final InterruptedException e) {
                        log.error("StreamThread was interrupted while waiting on empty topology", e);
                    }
                }
            } finally {
                unlock();
            }
        }
    }

    /**
     * Adds the topology and registers a future that listens for all threads on the older version to see the update
     */
    public void registerAndBuildNewTopology(final KafkaFutureImpl<Void> future, final InternalTopologyBuilder newTopologyBuilder) {
        try {
            lock();
            buildAndVerifyTopology(newTopologyBuilder);
            log.info("New NamedTopology passed validation and will be added {}, old topology version is {}", newTopologyBuilder.topologyName(), version.topologyVersion.get());
            version.topologyVersion.incrementAndGet();
            version.activeTopologyWaiters.add(new TopologyVersionWaiters(topologyVersion(), future));
            builders.put(newTopologyBuilder.topologyName(), newTopologyBuilder);
            wakeupThreads();
            log.info("Added NamedTopology {} and updated topology version to {}", newTopologyBuilder.topologyName(), version.topologyVersion.get());
        } catch (final Throwable throwable) {
            log.error("Failed to add NamedTopology {}, please retry the operation.", newTopologyBuilder.topologyName());
            future.completeExceptionally(throwable);
        } finally {
            unlock();
        }
    }

    /**
     * Removes the topology and registers a future that listens for all threads on the older version to see the update
     */
    public KafkaFuture<Void> unregisterTopology(final KafkaFutureImpl<Void> removeTopologyFuture,
                                                final String topologyName) {
        try {
            lock();
            log.info("Beginning removal of NamedTopology {}, old topology version is {}", topologyName, version.topologyVersion.get());
            version.topologyVersion.incrementAndGet();
            version.activeTopologyWaiters.add(new TopologyVersionWaiters(topologyVersion(), removeTopologyFuture));
            final InternalTopologyBuilder removedBuilder = builders.remove(topologyName);
            removedBuilder.fullSourceTopicNames().forEach(allInputTopics::remove);
            removedBuilder.allSourcePatternStrings().forEach(allInputTopics::remove);
            log.info("Finished removing NamedTopology {}, topology version was updated to {}", topologyName, version.topologyVersion.get());
        } catch (final Throwable throwable) {
            log.error("Failed to remove NamedTopology {}, please retry.", topologyName);
            removeTopologyFuture.completeExceptionally(throwable);
        } finally {
            unlock();
        }
        return removeTopologyFuture;
    }

    public TaskConfig getTaskConfigFor(final TaskId taskId) {
        final InternalTopologyBuilder builder = lookupBuilderForTask(taskId);
        return builder.topologyConfigs().getTaskConfig();
    }

    public void buildAndRewriteTopology() {
        applyToEachBuilder(this::buildAndVerifyTopology);
    }

    private void buildAndVerifyTopology(final InternalTopologyBuilder builder) {
        builder.rewriteTopology(config);
        builder.buildTopology();

        final Set<String> allInputTopicsCopy = new HashSet<>(allInputTopics);

        // As we go, check each topology for overlap in the set of input topics/patterns
        final int numInputTopics = allInputTopicsCopy.size();
        final List<String> inputTopics = builder.fullSourceTopicNames();
        final Collection<String> inputPatterns = builder.allSourcePatternStrings();

        final Set<String> newInputTopics = new HashSet<>(inputTopics);
        newInputTopics.addAll(inputPatterns);

        final int numNewInputTopics = newInputTopics.size();
        allInputTopicsCopy.addAll(newInputTopics);

        if (allInputTopicsCopy.size() != numInputTopics + numNewInputTopics) {
            inputTopics.retainAll(allInputTopicsCopy);
            inputPatterns.retainAll(allInputTopicsCopy);
            log.error("Tried to add the NamedTopology {} but it had overlap with other input topics {} or patterns {}",
                      builder.topologyName(), inputTopics, inputPatterns);
            throw new TopologyException("Named Topologies may not subscribe to the same input topics or patterns");
        }

        final ProcessorTopology globalTopology = builder.buildGlobalStateTopology();
        if (globalTopology != null) {
            if (builder.topologyName() != null) {
                throw new IllegalStateException("Global state stores are not supported with Named Topologies");
            } else if (this.globalTopology != null) {
                throw new TopologyException("Topology builder had global state, but global topology has already been set");
            } else {
                this.globalTopology = globalTopology;
                globalStateStores.putAll(builder.globalStateStores());
            }
        }
        allInputTopics.addAll(newInputTopics);
    }

    public int getNumStreamThreads(final StreamsConfig config) {
        final int configuredNumStreamThreads = config.getInt(StreamsConfig.NUM_STREAM_THREADS_CONFIG);

        // If there are named topologies but some are empty, this indicates a bug in user code
        if (hasNamedTopologies()) {
            if (hasNoLocalTopology()) {
                log.error("Detected a named topology with no input topics, a named topology may not be empty.");
                throw new TopologyException("Topology has no stream threads and no global threads, " +
                                                "must subscribe to at least one source topic or pattern.");
            }
        } else {
            // If both the global and non-global topologies are empty, this indicates a bug in user code
            if (hasNoLocalTopology() && !hasGlobalTopology()) {
                log.error("Topology with no input topics will create no stream threads and no global thread.");
                throw new TopologyException("Topology has no stream threads and no global threads, " +
                                                "must subscribe to at least one source topic or global table.");
            }
        }

        // Lastly we check for an empty non-global topology and override the threads to zero if set otherwise
        if (configuredNumStreamThreads != 0 && hasNoLocalTopology()) {
            log.info("Overriding number of StreamThreads to zero for global-only topology");
            return 0;
        }

        return configuredNumStreamThreads;
    }

    /**
     * @return true iff the app is using named topologies, or was started up with no topology at all
     */
    public boolean hasNamedTopologies() {
        return !builders.containsKey(UNNAMED_TOPOLOGY);
    }

    public Set<String> namedTopologiesView() {
        return hasNamedTopologies() ? Collections.unmodifiableSet(builders.keySet()) : emptySet();
    }

    /**
     * @return true iff any of the topologies have a global topology
     */
    public boolean hasGlobalTopology() {
        return evaluateConditionIsTrueForAnyBuilders(InternalTopologyBuilder::hasGlobalStores);
    }

    /**
     * @return true iff any of the topologies have no local (aka non-global) topology
     */
    public boolean hasNoLocalTopology() {
        return evaluateConditionIsTrueForAnyBuilders(InternalTopologyBuilder::hasNoLocalTopology);
    }

    public boolean hasPersistentStores() {
        // If the app is using named topologies, there may not be any persistent state when it first starts up
        // but a new NamedTopology may introduce it later, so we must return true
        if (hasNamedTopologies()) {
            return true;
        }
        return evaluateConditionIsTrueForAnyBuilders(InternalTopologyBuilder::hasPersistentStores);
    }

    public boolean hasStore(final String name) {
        return evaluateConditionIsTrueForAnyBuilders(b -> b.hasStore(name));
    }

    public boolean hasOffsetResetOverrides() {
        // Return true if using named topologies, as there may be named topologies added later which do have overrides
        return hasNamedTopologies() || evaluateConditionIsTrueForAnyBuilders(InternalTopologyBuilder::hasOffsetResetOverrides);
    }

    public OffsetResetStrategy offsetResetStrategy(final String topic) {
        for (final InternalTopologyBuilder builder : builders.values()) {
            final OffsetResetStrategy resetStrategy = builder.offsetResetStrategy(topic);
            if (resetStrategy != null) {
                return resetStrategy;
            }
        }
        return null;
    }

    public Collection<String> fullSourceTopicNamesForTopology(final String topologyName) {
        Objects.requireNonNull(topologyName, "topology name must not be null");
        return lookupBuilderForNamedTopology(topologyName).fullSourceTopicNames();
    }

    public Collection<String> allFullSourceTopicNames() {
        final List<String> sourceTopics = new ArrayList<>();
        applyToEachBuilder(b -> sourceTopics.addAll(b.fullSourceTopicNames()));
        return sourceTopics;
    }

    Pattern sourceTopicPattern() {
        final StringBuilder patternBuilder = new StringBuilder();

        applyToEachBuilder(b -> {
            final String patternString = b.sourceTopicsPatternString();
            if (patternString.length() > 0) {
                patternBuilder.append(patternString).append("|");
            }
        });

        if (patternBuilder.length() > 0) {
            patternBuilder.setLength(patternBuilder.length() - 1);
            return Pattern.compile(patternBuilder.toString());
        } else {
            return EMPTY_ZERO_LENGTH_PATTERN;
        }
    }

    public boolean usesPatternSubscription() {
        return evaluateConditionIsTrueForAnyBuilders(InternalTopologyBuilder::usesPatternSubscription);
    }

    // Can be empty if app is started up with no Named Topologies, in order to add them on later
    public boolean isEmpty() {
        return builders.isEmpty();
    }

    public String topologyDescriptionString() {
        if (isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();

        applyToEachBuilder(b -> sb.append(b.describe().toString()));

        return sb.toString();
    }

    /**
     * @return the {@link ProcessorTopology subtopology} built for this task, guaranteed to be non-null
     *
     * @throws UnknownTopologyException  if the task is from a named topology that this client isn't aware of
     */
    public ProcessorTopology buildSubtopology(final TaskId task) {
        final InternalTopologyBuilder builder = lookupBuilderForTask(task);
        return builder.buildSubtopology(task.subtopology());
    }

    public ProcessorTopology globalTaskTopology() {
        if (hasNamedTopologies()) {
            throw new IllegalStateException("Global state stores are not supported with Named Topologies");
        }
        return globalTopology;
    }

    public Map<String, StateStore> globalStateStores() {
        return globalStateStores;
    }

    public Map<String, List<String>> stateStoreNameToSourceTopicsForTopology(final String topologyName) {
        return lookupBuilderForNamedTopology(topologyName).stateStoreNameToFullSourceTopicNames();
    }

    public Map<String, List<String>> stateStoreNameToSourceTopics() {
        final Map<String, List<String>> stateStoreNameToSourceTopics = new HashMap<>();
        applyToEachBuilder(b -> stateStoreNameToSourceTopics.putAll(b.stateStoreNameToFullSourceTopicNames()));
        return stateStoreNameToSourceTopics;
    }

    public String getStoreForChangelogTopic(final String topicName) {
        for (final InternalTopologyBuilder builder : builders.values()) {
            final String store = builder.getStoreForChangelogTopic(topicName);
            if (store != null) {
                return store;
            }
        }
        log.warn("Unable to locate any store for topic {}", topicName);
        return "";
    }

    /**
     * @param storeName       the name of the state store
     * @param topologyName    the name of the topology to search for stores within
     * @return topics subscribed from source processors that are connected to these state stores
     */
    public Collection<String> sourceTopicsForStore(final String storeName, final String topologyName) {
        return lookupBuilderForNamedTopology(topologyName).sourceTopicsForStore(storeName);
    }

    public static String getTopologyNameOrElseUnnamed(final String topologyName) {
        return topologyName == null ? UNNAMED_TOPOLOGY : topologyName;
    }

    /**
     * @param topologiesToExclude the names of any topologies to exclude from the returned topic groups,
     *                            eg because they have missing source topics and can't be processed yet
     *
     * @return                    flattened map of all subtopologies (from all topologies) to topics info
     */
    public Map<Subtopology, TopicsInfo> subtopologyTopicsInfoMapExcluding(final Set<String> topologiesToExclude) {
        final Map<Subtopology, TopicsInfo> subtopologyTopicsInfo = new HashMap<>();
        applyToEachBuilder(b -> {
            if (!topologiesToExclude.contains(b.topologyName())) {
                subtopologyTopicsInfo.putAll(b.subtopologyToTopicsInfo());
            }
        });
        return subtopologyTopicsInfo;
    }

    /**
     * @return    map from topology to its subtopologies and their topics info
     */
    public Map<String, Map<Subtopology, TopicsInfo>> topologyToSubtopologyTopicsInfoMap() {
        final Map<String, Map<Subtopology, TopicsInfo>> topologyToSubtopologyTopicsInfoMap = new HashMap<>();
        applyToEachBuilder(b -> topologyToSubtopologyTopicsInfoMap.put(b.topologyName(), b.subtopologyToTopicsInfo()));
        return  topologyToSubtopologyTopicsInfoMap;
    }

    public Map<String, List<String>> nodeToSourceTopics(final TaskId task) {
        return lookupBuilderForTask(task).nodeToSourceTopics();
    }

    void addSubscribedTopicsFromMetadata(final Set<String> topics, final String logPrefix) {
        applyToEachBuilder(b -> b.addSubscribedTopicsFromMetadata(topics, logPrefix));
    }

    void addSubscribedTopicsFromAssignment(final List<TopicPartition> partitions, final String logPrefix) {
        applyToEachBuilder(b -> b.addSubscribedTopicsFromAssignment(partitions, logPrefix));
    }

    public Collection<Set<String>> copartitionGroups() {
        final List<Set<String>> copartitionGroups = new ArrayList<>();
        applyToEachBuilder(b -> copartitionGroups.addAll(b.copartitionGroups()));
        return copartitionGroups;
    }

    /**
     * @return the {@link InternalTopologyBuilder} for this task's topology, guaranteed to be non-null
     *
     * @throws UnknownTopologyException  if the task is from a named topology that this client isn't aware of
     */
    private InternalTopologyBuilder lookupBuilderForTask(final TaskId task) {
        final InternalTopologyBuilder builder = task.topologyName() == null ?
            builders.get(UNNAMED_TOPOLOGY) :
            builders.get(task.topologyName());
        if (builder == null) {
            throw new UnknownTopologyException("Unable to locate topology builder", task.topologyName());
        } else {
            return builder;
        }
    }


    /**
     * @return the InternalTopologyBuilder for the NamedTopology with the given {@code topologyName}
     *         or the builder for a regular Topology if {@code topologyName} is {@code null},
     *         else returns {@code null} if {@code topologyName} is non-null but no such NamedTopology exists
     */
    public InternalTopologyBuilder lookupBuilderForNamedTopology(final String topologyName) {
        if (topologyName == null) {
            return builders.get(UNNAMED_TOPOLOGY);
        } else {
            return builders.get(topologyName);
        }
    }

    private boolean evaluateConditionIsTrueForAnyBuilders(final Function<InternalTopologyBuilder, Boolean> condition) {
        for (final InternalTopologyBuilder builder : builders.values()) {
            if (condition.apply(builder)) {
                return true;
            }
        }
        return false;
    }

    private void applyToEachBuilder(final Consumer<InternalTopologyBuilder> function) {
        for (final InternalTopologyBuilder builder : builders.values()) {
            function.accept(builder);
        }
    }

    public static class Subtopology {
        final int nodeGroupId;
        final String namedTopology;

        public Subtopology(final int nodeGroupId, final String namedTopology) {
            this.nodeGroupId = nodeGroupId;
            this.namedTopology = namedTopology;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final Subtopology that = (Subtopology) o;
            return nodeGroupId == that.nodeGroupId &&
                    Objects.equals(namedTopology, that.namedTopology);
        }

        @Override
        public int hashCode() {
            return Objects.hash(nodeGroupId, namedTopology);
        }
    }
}
