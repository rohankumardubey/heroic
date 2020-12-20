/*
 * Copyright (c) 2015 Spotify AB.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.heroic.metric;

import static com.spotify.heroic.common.Optionals.mergeOptionalList;
import static com.spotify.heroic.common.Optionals.pickOptional;
import static com.spotify.heroic.metric.consts.ApiQueryConsts.DEFAULT_MAX_ELAPSED_BACKOFF_MILLIS;
import static com.spotify.heroic.metric.consts.ApiQueryConsts.DEFAULT_MAX_SCAN_TIMEOUT_RETRIES;
import static com.spotify.heroic.metric.consts.ApiQueryConsts.DEFAULT_MUTATE_RPC_TIMEOUT_MS;
import static com.spotify.heroic.metric.consts.ApiQueryConsts.DEFAULT_READ_ROWS_RPC_TIMEOUT_MS;
import static com.spotify.heroic.metric.consts.ApiQueryConsts.DEFAULT_SHORT_RPC_TIMEOUT_MS;
import static java.util.Optional.empty;
import static java.util.Optional.of;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.spotify.heroic.analytics.MetricAnalytics;
import com.spotify.heroic.common.GroupSet;
import com.spotify.heroic.common.ModuleIdBuilder;
import com.spotify.heroic.common.OptionalLimit;
import com.spotify.heroic.dagger.CorePrimaryComponent;
import com.spotify.heroic.lifecycle.LifeCycle;
import com.spotify.heroic.statistics.HeroicReporter;
import com.spotify.heroic.statistics.MetricBackendReporter;
import dagger.Module;
import dagger.Provides;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.inject.Named;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Module
@SuppressWarnings({"LineLength"})
public class MetricManagerModule {
    private static final Logger log = LoggerFactory.getLogger(MetricManagerModule.class);

    public static final int DEFAULT_FETCH_PARALLELISM = 100;
    public static final boolean DEFAULT_FAIL_ON_LIMITS = false;
    public static final long DEFAULT_SMALL_QUERY_THRESHOLD = 200000;

    public final List<MetricModule> backends;
    public final Optional<List<String>> defaultBackends;

    /**
     * Limit in how many groups we are allowed to return.
     */
    private final OptionalLimit groupLimit;

    /**
     * Limit in the number of series we may fetch from the metadata backend.
     */
    private final OptionalLimit seriesLimit;

    /**
     * Limit in how many datapoints a single aggregation is allowed to output.
     */
    private final OptionalLimit aggregationLimit;

    /**
     * Limit in how many datapoints a session is allowed to fetch in total.
     */
    private final OptionalLimit dataLimit;

    /**
     * Limit how many concurrent queries that the MetricManager will accept. When this level is
     * reached, the result will be back-off so that another node in the cluster can be used instead.
     */
    private final OptionalLimit concurrentQueriesBackoff;

    /**
     * How many data fetches are performed in parallel.
     */
    private final int fetchParallelism;

    /**
     * If {@code true}, will cause any limits applied to be reported as a failure.
     */
    private final boolean failOnLimits;

    /**
     * Threshold for defining a "small" query, measured in pre-aggregation sample size
     */
    private final long smallQueryThreshold;

    /**
     * @See com.spotify.heroic.metric.consts.ApiQueryConsts#DEFAULT_MUTATE_RPC_TIMEOUT_MS
     */
    private final int mutateRpcTimeoutMs;

    /**
     * @See com.spotify.heroic.metric.consts.ApiQueryConsts#DEFAULT_READ_ROWS_RPC_TIMEOUT_MS
     */
    private final int readRowsRpcTimeoutMs;

    /**
     * @See com.spotify.heroic.metric.consts.ApiQueryConsts#DEFAULT_SHORT_RPC_TIMEOUT_MS
     */
    private final int shortRpcTimeoutMs;

    /**
     * @See com.spotify.heroic.metric.consts.ApiQueryConsts#DEFAULT_MAX_SCAN_TIMEOUT_RETRIES
     */
    private final int maxScanTimeoutRetries;

    /**
     * @See com.spotify.heroic.metric.consts.ApiQueryConsts#DEFAULT_MAX_ELAPSED_BACKOFF_MILLIS
     */
    private final int maxElapsedBackoffMs;

    private MetricManagerModule(
        List<MetricModule> backends,
        Optional<List<String>> defaultBackends,
        OptionalLimit groupLimit,
        OptionalLimit seriesLimit,
        OptionalLimit aggregationLimit,
        OptionalLimit dataLimit,
        OptionalLimit concurrentQueriesBackoff,
        int fetchParallelism,
        boolean failOnLimits,
        long smallQueryThreshold,
        int mutateRpcTimeoutMs,
        int readRowsRpcTimeoutMs,
        int shortRpcTimeoutMs,
        int maxScanTimeoutRetries,
        int maxElapsedBackoffMs
    ) {
        this.backends = backends;
        this.defaultBackends = defaultBackends;
        this.groupLimit = groupLimit;
        this.seriesLimit = seriesLimit;
        this.aggregationLimit = aggregationLimit;
        this.dataLimit = dataLimit;
        this.concurrentQueriesBackoff = concurrentQueriesBackoff;
        this.fetchParallelism = fetchParallelism;
        this.failOnLimits = failOnLimits;
        this.smallQueryThreshold = smallQueryThreshold;
        this.mutateRpcTimeoutMs = mutateRpcTimeoutMs;
        this.readRowsRpcTimeoutMs = readRowsRpcTimeoutMs;
        this.shortRpcTimeoutMs = shortRpcTimeoutMs;
        this.maxScanTimeoutRetries = maxScanTimeoutRetries;
        this.maxElapsedBackoffMs = maxElapsedBackoffMs;

        log.info("Metric Manager Module: \n{}", toString());
    }

    @Provides
    @MetricScope
    public MetricBackendReporter reporter(HeroicReporter reporter) {
        return reporter.newMetricBackend();
    }

    @Provides
    @MetricScope
    public GroupSet<MetricBackend> defaultBackends(
        Set<MetricBackend> configured, MetricAnalytics analytics
    ) {
        return GroupSet.build(
            ImmutableSet.copyOf(configured.stream().map(analytics::wrap).iterator()),
            defaultBackends);
    }

    @Provides
    @MetricScope
    public List<MetricModule.Exposed> components(
        final CorePrimaryComponent primary, final MetricBackendReporter reporter
    ) {
        final List<MetricModule.Exposed> exposed = new ArrayList<>();

        final ModuleIdBuilder idBuilder = new ModuleIdBuilder();

        for (final MetricModule m : backends) {
            final String id = idBuilder.buildId(m);

            final MetricModule.Depends depends = new MetricModule.Depends(reporter);
            exposed.add(m.module(primary, depends, id));
        }

        return exposed;
    }

    @Provides
    @MetricScope
    public Set<MetricBackend> backends(
        List<MetricModule.Exposed> components, MetricBackendReporter reporter
    ) {
        return ImmutableSet.copyOf(components
            .stream()
            .map(MetricModule.Exposed::backend)
            .map(reporter::decorate)
            .iterator());
    }

    @Provides
    @MetricScope
    @Named("metric")
    public LifeCycle metricLife(List<MetricModule.Exposed> components) {
        return LifeCycle.combined(components.stream().map(MetricModule.Exposed::life));
    }

    @Provides
    @MetricScope
    @Named("groupLimit")
    public OptionalLimit groupLimit() {
        return groupLimit;
    }

    @Provides
    @MetricScope
    @Named("seriesLimit")
    public OptionalLimit seriesLimit() {
        return seriesLimit;
    }

    @Provides
    @MetricScope
    @Named("aggregationLimit")
    public OptionalLimit aggregationLimit() {
        return aggregationLimit;
    }

    @Provides
    @MetricScope
    @Named("dataLimit")
    public OptionalLimit dataLimit() {
        return dataLimit;
    }

    @Provides
    @MetricScope
    @Named("concurrentQueriesBackoff")
    public OptionalLimit concurrentQueriesBackoff() {
        return concurrentQueriesBackoff;
    }

    @Provides
    @MetricScope
    @Named("fetchParallelism")
    public int fetchParallelism() {
        return fetchParallelism;
    }

    @Provides
    @MetricScope
    @Named("failOnLimits")
    public boolean failOnLimits() {
        return failOnLimits;
    }

    @Provides
    @MetricScope
    @Named("smallQueryThreshold")
    public long smallQueryThreshold() {
        return smallQueryThreshold;
    }

    @Provides
    @MetricScope
    @Named("mutateRpcTimeoutMs")
    public int mutateRpcTimeoutMs() {
        return mutateRpcTimeoutMs;
    }

    @Provides
    @MetricScope
    @Named("readRowsRpcTimeoutMs")
    public int readRowsRpcTimeoutMs() {
        return readRowsRpcTimeoutMs;
    }

    @Provides
    @MetricScope
    @Named("shortRpcTimeoutMs")
    public int shortRpcTimeoutMs() {
        return shortRpcTimeoutMs;
    }

    @Provides
    @MetricScope
    @Named("maxScanTimeoutRetries")
    public int maxScanTimeoutRetries() {
        return maxScanTimeoutRetries;
    }

    @Provides
    @MetricScope
    @Named("maxElapsedBackoffMs")
    public int maxElapsedBackoffMs() {
        return maxElapsedBackoffMs;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("backends", backends)
            .append("defaultBackends", defaultBackends.orElse(new ArrayList<String>()))
            .append("groupLimit", groupLimit)
            .append("seriesLimit", seriesLimit)
            .append("aggregationLimit", aggregationLimit)
            .append("dataLimit", dataLimit)
            .append("concurrentQueriesBackoff", concurrentQueriesBackoff)
            .append("fetchParallelism", fetchParallelism)
            .append("failOnLimits", failOnLimits)
            .append("smallQueryThreshold", smallQueryThreshold)
            .append("mutateRpcTimeoutMs", mutateRpcTimeoutMs)
            .append("readRowsRpcTimeoutMs", readRowsRpcTimeoutMs)
            .append("shortRpcTimeoutMs", shortRpcTimeoutMs)
            .append("maxScanTimeoutRetries", maxScanTimeoutRetries)
            .append("maxElapsedBackoffMs", maxElapsedBackoffMs)
            .toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Optional<List<MetricModule>> backends = empty();
        private Optional<List<String>> defaultBackends = empty();
        private OptionalLimit groupLimit = OptionalLimit.empty();
        private OptionalLimit seriesLimit = OptionalLimit.empty();
        private OptionalLimit aggregationLimit = OptionalLimit.empty();
        private OptionalLimit dataLimit = OptionalLimit.empty();
        private OptionalLimit concurrentQueriesBackoff = OptionalLimit.empty();
        private Optional<Integer> fetchParallelism = empty();
        private Optional<Boolean> failOnLimits = empty();
        private Optional<Long> smallQueryThreshold = empty();
        /**
         * See com.spotify.heroic.metric.consts.ApiQueryConsts#DEFAULT_MUTATE_RPC_TIMEOUT_MS
         */
        private Optional<Integer> mutateRpcTimeoutMs = empty();
        /**
         * See com.spotify.heroic.metric.consts.ApiQueryConsts#DEFAULT_READ_ROWS_RPC_TIMEOUT_MS
         */
        private Optional<Integer> readRowsRpcTimeoutMs = empty();
        /**
         * See com.spotify.heroic.metric.consts.ApiQueryConsts#DEFAULT_SHORT_RPC_TIMEOUT_MS
         */
        private Optional<Integer> shortRpcTimeoutMs = empty();
        /**
         * See com.spotify.heroic.metric.consts.ApiQueryConsts#DEFAULT_MAX_SCAN_TIMEOUT_RETRIES
         */
        private Optional<Integer> maxScanTimeoutRetries = empty();
        /**
         * See com.spotify.heroic.metric.consts.ApiQueryConsts#DEFAULT_MAX_ELAPSED_BACKOFF_MILLIS
         */
        private Optional<Integer> maxElapsedBackoffMs = empty();

        private Builder() {
        }

        @JsonCreator
        public Builder(
            @JsonProperty("backends") Optional<List<MetricModule>> backends,
            @JsonProperty("defaultBackends") Optional<List<String>> defaultBackends,
            @JsonProperty("groupLimit") OptionalLimit groupLimit,
            @JsonProperty("seriesLimit") OptionalLimit seriesLimit,
            @JsonProperty("aggregationLimit") OptionalLimit aggregationLimit,
            @JsonProperty("dataLimit") OptionalLimit dataLimit,
            @JsonProperty("concurrentQueriesBackoff") OptionalLimit concurrentQueriesBackoff,
            @JsonProperty("fetchParallelism") Optional<Integer> fetchParallelism,
            @JsonProperty("failOnLimits") Optional<Boolean> failOnLimits,
            @JsonProperty("smallQueryThreshold") Optional<Long> smallQueryThreshold,
            @JsonProperty("mutateRpcTimeoutMs") Optional<Integer> mutateRpcTimeoutMs,
            @JsonProperty("readRowsRpcTimeoutMs") Optional<Integer> readRowsRpcTimeoutMs,
            @JsonProperty("shortRpcTimeoutMs") Optional<Integer> shortRpcTimeoutMs,
            @JsonProperty("maxScanTimeoutRetries") Optional<Integer> maxScanTimeoutRetries,
            @JsonProperty("maxElapsedBackoffMs") Optional<Integer> maxElapsedBackoffMs
        ) {
            this.backends = backends;
            this.defaultBackends = defaultBackends;
            this.groupLimit = groupLimit;
            this.seriesLimit = seriesLimit;
            this.aggregationLimit = aggregationLimit;
            this.dataLimit = dataLimit;
            this.concurrentQueriesBackoff = concurrentQueriesBackoff;
            this.fetchParallelism = fetchParallelism;
            this.failOnLimits = failOnLimits;
            this.smallQueryThreshold = smallQueryThreshold;
            this.mutateRpcTimeoutMs = mutateRpcTimeoutMs;
            this.readRowsRpcTimeoutMs = readRowsRpcTimeoutMs;
            this.shortRpcTimeoutMs = shortRpcTimeoutMs;
            this.maxScanTimeoutRetries = maxScanTimeoutRetries;
            this.maxElapsedBackoffMs = maxElapsedBackoffMs;
        }

        public Builder backends(List<MetricModule> backends) {
            this.backends = of(backends);
            return this;
        }

        public Builder defaultBackends(List<String> defaultBackends) {
            this.defaultBackends = of(defaultBackends);
            return this;
        }

        public Builder groupLimit(long groupLimit) {
            this.groupLimit = OptionalLimit.of(groupLimit);
            return this;
        }

        public Builder seriesLimit(long seriesLimit) {
            this.seriesLimit = OptionalLimit.of(seriesLimit);
            return this;
        }

        public Builder aggregationLimit(long aggregationLimit) {
            this.aggregationLimit = OptionalLimit.of(aggregationLimit);
            return this;
        }

        public Builder dataLimit(long dataLimit) {
            this.dataLimit = OptionalLimit.of(dataLimit);
            return this;
        }

        public Builder concurrentQueriesBackoff(int concurrentQueriesBackoff) {
            this.concurrentQueriesBackoff = OptionalLimit.of(concurrentQueriesBackoff);
            return this;
        }

        public Builder fetchParallelism(Integer fetchParallelism) {
            this.fetchParallelism = of(fetchParallelism);
            return this;
        }

        public Builder failOnLimits(boolean failOnLimits) {
            this.failOnLimits = of(failOnLimits);
            return this;
        }

        public Builder smallQueryThreshold(long smallQueryThreshold) {
            this.smallQueryThreshold = of(smallQueryThreshold);
            return this;
        }

        public Builder mutateRpcTimeoutMs(int mutateRpcTimeoutMs) {
            this.mutateRpcTimeoutMs = of(mutateRpcTimeoutMs);
            return this;
        }

        public Builder readRowsRpcTimeoutMs(int readRowsRpcTimeoutMs) {
            this.readRowsRpcTimeoutMs = of(readRowsRpcTimeoutMs);
            return this;
        }

        public Builder shortReadRpcTimeoutMs(int shortRpcTimeoutMs) {
            this.shortRpcTimeoutMs = of(shortRpcTimeoutMs);
            return this;
        }

        public Builder maxScanTimeoutRetries(int maxScanTimeoutRetries) {
            this.maxScanTimeoutRetries = of(maxScanTimeoutRetries);
            return this;
        }

        public Builder maxElapsedBackoffMs(int maxElapsedBackoffMs) {
            this.maxElapsedBackoffMs = of(maxElapsedBackoffMs);
            return this;
        }

        public Builder merge(final Builder o) {
            // @formatter:off
            return new Builder(
                mergeOptionalList(o.backends, backends),
                mergeOptionalList(o.defaultBackends, defaultBackends),
                groupLimit.orElse(o.groupLimit),
                seriesLimit.orElse(o.seriesLimit),
                aggregationLimit.orElse(o.aggregationLimit),
                dataLimit.orElse(o.dataLimit),
                concurrentQueriesBackoff.orElse(o.concurrentQueriesBackoff),
                pickOptional(fetchParallelism, o.fetchParallelism),
                pickOptional(failOnLimits, o.failOnLimits),
                pickOptional(smallQueryThreshold, o.smallQueryThreshold),
                pickOptional(mutateRpcTimeoutMs, o.mutateRpcTimeoutMs),
                pickOptional(readRowsRpcTimeoutMs, o.readRowsRpcTimeoutMs),
                pickOptional(shortRpcTimeoutMs, o.shortRpcTimeoutMs),
                pickOptional(maxScanTimeoutRetries, o.maxScanTimeoutRetries),
                pickOptional(maxElapsedBackoffMs, o.maxElapsedBackoffMs)
            );
            // @formatter:on
        }

        public MetricManagerModule build() {
            // @formatter:off
            return new MetricManagerModule(
                backends.orElseGet(ImmutableList::of),
                defaultBackends,
                groupLimit,
                seriesLimit,
                aggregationLimit,
                dataLimit,
                concurrentQueriesBackoff,
                fetchParallelism.orElse(DEFAULT_FETCH_PARALLELISM),
                failOnLimits.orElse(DEFAULT_FAIL_ON_LIMITS),
                smallQueryThreshold.orElse(DEFAULT_SMALL_QUERY_THRESHOLD),
                mutateRpcTimeoutMs.orElse(DEFAULT_MUTATE_RPC_TIMEOUT_MS),
                readRowsRpcTimeoutMs.orElse(DEFAULT_READ_ROWS_RPC_TIMEOUT_MS),
                shortRpcTimeoutMs.orElse(DEFAULT_SHORT_RPC_TIMEOUT_MS),
                maxScanTimeoutRetries.orElse(DEFAULT_MAX_SCAN_TIMEOUT_RETRIES),
                maxElapsedBackoffMs.orElse(DEFAULT_MAX_ELAPSED_BACKOFF_MILLIS)
            );
            // @formatter:on
        }
    }
}
