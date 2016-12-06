/*
 * Copyright 2016 James Hood
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

package com.blacklocus.metrics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.Future;

import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsync;
import com.amazonaws.services.cloudwatch.model.MetricDatum;
import com.amazonaws.services.cloudwatch.model.PutMetricDataRequest;
import com.amazonaws.services.cloudwatch.model.StandardUnit;
import com.amazonaws.services.cloudwatch.model.StatisticSet;
import com.codahale.metrics.Counter;
import com.codahale.metrics.Counting;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Reporter;
import com.codahale.metrics.Sampling;
import com.codahale.metrics.Snapshot;
import com.codahale.metrics.Timer;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Please refer to [README.md](https://github.com/blacklocus/metrics-cloudwatch/blob/master/README.md) for the
 * latest usage documentation.
 */
public class CloudWatchReporter implements Reporter {

    private static final Logger LOG = LoggerFactory.getLogger(CloudWatchReporter.class);

    /**
     * Submit metrics to CloudWatch under this metric namespace
     */
    private final String metricNamespace;

    private final AmazonCloudWatchAsync cloudWatch;

    /**
     * We only submit the difference in counters since the last submission. This way we don't have to reset the counters
     * within this application.
     */
    private final Map<Counting, Long> lastPolledCounts = new HashMap<Counting, Long>();


    /**
     * Optional, global reporter-wide dimensions automatically appended to all metrics.
     */
    private String dimensions;

    /**
     * Whether or not to explicitly timestamp metric data to local now (true), or leave it null so that
     * CloudWatch will timestamp it on receipt (false). Defaults to false.
     */
    private boolean timestampLocal = false;

    /**
     * This filter is applied right before submission to CloudWatch. This filter can access decoded metric name elements
     * such as {@link MetricDatum#getDimensions()}.
     * <p>
     * Different from {@link MetricFilter} in that
     * MetricFilter must operate on the encoded, single-string name (see {@link MetricFilter#matches(String, Metric)}),
     * and this filter is applied before {@link #report(SortedMap, SortedMap, SortedMap, SortedMap, SortedMap)} so that
     * filtered metrics never reach that method in this reporter.
     * <p>
     * Defaults to {@link Predicates#alwaysTrue()} - i.e. do not remove any metrics from the submission due to this
     * particular filter.
     */
    private Predicate<MetricDatum> reporterFilter = Predicates.alwaysTrue();

    // Default settings
    private String typeDimName = Constants.DEF_DIM_NAME_TYPE;
    private String typeDimValGauge = Constants.DEF_DIM_VAL_GAUGE;
    private String typeDimValCounterCount = Constants.DEF_DIM_VAL_COUNTER_COUNT;
    private String typeDimValMeterCount = Constants.DEF_DIM_VAL_METER_COUNT;
    private String typeDimValHistoSamples = Constants.DEF_DIM_VAL_HISTO_SAMPLES;
    private String typeDimValHistoStats = Constants.DEF_DIM_VAL_HISTO_STATS;
    private String typeDimValTimerSamples = Constants.DEF_DIM_VAL_TIMER_SAMPLES;
    private String typeDimValTimerStats = Constants.DEF_DIM_VAL_TIMER_STATS;


    /**
     * Creates a new {@link CloudWatchReporter} instance. The reporter does not report metrics until
     * {@link #report} is called.
     *
     * @param cloudWatch client
     */
    public CloudWatchReporter(AmazonCloudWatchAsync cloudWatch) {
        this(null, cloudWatch);
    }

    /**
     * Creates a new {@link CloudWatchReporter} instance. The reporter does not report metrics until
     * {@link #report} is called.
     *
     * @param metricNamespace (optional) CloudWatch metric namespace that all metrics reported by this reporter will
     *                        fall under
     * @param cloudWatch      client
     */
    public CloudWatchReporter(String metricNamespace,
                              AmazonCloudWatchAsync cloudWatch) {
        this.metricNamespace = metricNamespace;
        this.cloudWatch = cloudWatch;
    }

    /**
     * Sets global reporter-wide dimensions and returns itself.
     *
     * @param dimensions (optional) the string representing global dimensions
     * @return this (for chaining)
     */
    public CloudWatchReporter withDimensions(String dimensions) {
        this.dimensions = dimensions;
        return this;
    }

    /**
     * @param timestampLocal Whether or not to explicitly timestamp metric data to now (true), or leave it null so that
     *                       CloudWatch will timestamp it on receipt (false). Defaults to false.
     * @return this (for chaining)
     */
    public CloudWatchReporter withTimestampLocal(boolean timestampLocal) {
        this.timestampLocal = timestampLocal;
        return this;
    }

    /**
     * @param typeDimName name of the "metric type" dimension added to CloudWatch submissions.
     *                    Defaults to <b>{@value Constants#DEF_DIM_NAME_TYPE}</b>.
     * @return this (for chaining)
     */
    public CloudWatchReporter withTypeDimName(String typeDimName) {
        this.typeDimName = typeDimName;
        return this;
    }

    /**
     * @param typeDimValGauge value of the "metric type" dimension added to CloudWatch submissions of {@link Gauge}s.
     *                        Defaults to <b>{@value Constants#DEF_DIM_VAL_GAUGE}</b> when using either
     *                        CloudWatchReporter constructors directly (backwards-compatibility) or
     *                        when using the CloudWatchReporterBuilder
     * @return this (for chaining)
     */
    public CloudWatchReporter withTypeDimValGauge(String typeDimValGauge) {
        this.typeDimValGauge = typeDimValGauge;
        return this;
    }

    /**
     * @param typeDimValCounterCount value of the "metric type" dimension added to CloudWatch submissions of
     *                               {@link Counter#getCount()}.
     *                               Defaults to <b>{@value Constants#DEF_DIM_VAL_COUNTER_COUNT}</b>.
     * @return this (for chaining)
     */
    public CloudWatchReporter withTypeDimValCounterCount(String typeDimValCounterCount) {
        this.typeDimValCounterCount = typeDimValCounterCount;
        return this;
    }

    /**
     * @param typeDimValMeterCount value of the "metric type" dimension added to CloudWatch submissions of
     *                             {@link Meter#getCount()}.
     *                             Defaults to <b>{@value Constants#DEF_DIM_VAL_METER_COUNT}</b>.
     * @return this (for chaining)
     */
    public CloudWatchReporter withTypeDimValMeterCount(String typeDimValMeterCount) {
        this.typeDimValMeterCount = typeDimValMeterCount;
        return this;
    }

    /**
     * @param typeDimValHistoSamples value of the "metric type" dimension added to CloudWatch submissions of
     *                               {@link Histogram#getCount()}.
     *                               Defaults to <b>{@value Constants#DEF_DIM_VAL_HISTO_SAMPLES}</b>.
     * @return this (for chaining)
     */
    public CloudWatchReporter withTypeDimValHistoSamples(String typeDimValHistoSamples) {
        this.typeDimValHistoSamples = typeDimValHistoSamples;
        return this;
    }

    /**
     * @param typeDimValHistoStats value of the "metric type" dimension added to CloudWatch submissions of
     *                             {@link Histogram#getSnapshot()}.
     *                             Defaults to <b>{@value Constants#DEF_DIM_VAL_HISTO_STATS}</b>.
     * @return this (for chaining)
     */
    public CloudWatchReporter withTypeDimValHistoStats(String typeDimValHistoStats) {
        this.typeDimValHistoStats = typeDimValHistoStats;
        return this;
    }

    /**
     * @param typeDimValTimerSamples value of the "metric type" dimension added to CloudWatch submissions of
     *                               {@link Timer#getCount()}.
     *                               Defaults to <b>{@value Constants#DEF_DIM_VAL_TIMER_SAMPLES}</b>.
     * @return this (for chaining)
     */
    public CloudWatchReporter withTypeDimValTimerSamples(String typeDimValTimerSamples) {
        this.typeDimValTimerSamples = typeDimValTimerSamples;
        return this;
    }

    /**
     * @param typeDimValTimerStats value of the "metric type" dimension added to CloudWatch submissions of
     *                             {@link Timer#getSnapshot()}.
     *                             Defaults to <b>{@value Constants#DEF_DIM_VAL_TIMER_STATS}</b>.
     * @return this (for chaining)
     */
    public CloudWatchReporter withTypeDimValTimerStats(String typeDimValTimerStats) {
        this.typeDimValTimerStats = typeDimValTimerStats;
        return this;
    }

    /**
     * This filter is applied right before submission to CloudWatch. This filter can access decoded metric name elements
     * such as {@link MetricDatum#getDimensions()}.
     * <p>
     * Different from {@link MetricFilter} in that
     * MetricFilter must operate on the encoded, single-string name (see {@link MetricFilter#matches(String, Metric)}),
     * and this filter is applied before {@link #report(SortedMap, SortedMap, SortedMap, SortedMap, SortedMap)} so that
     * filtered metrics never reach that method in this reporter.
     * <p>
     * Defaults to {@link Predicates#alwaysTrue()} - i.e. do not remove any metrics from the submission due to this
     * particular filter.
     *
     * @param reporterFilter to replace 'alwaysTrue()'
     * @return this (for chaining)
     */
    public CloudWatchReporter withReporterFilter(Predicate<MetricDatum> reporterFilter) {
        this.reporterFilter = reporterFilter;
        return this;
    }

    /**
     * Returns the CloudWatch metric namespace that all metrics reported by this reporter will fall under.
     *
     * @return the metric namespace.
     */
    public String getMetricNamespace() {
        return metricNamespace;
    }

    /**
     * Reports metrics in the given registry to CloudWatch.
     *
     * @param registry metrics registry.
     */
    public void report(MetricRegistry registry) {
        report(registry.getGauges(), registry.getCounters(), registry.getHistograms(), registry.getMeters(), registry.getTimers());
    }

    /**
     * Reports the given metrics to CloudWatch.
     *
     * @param gauges     gauge metrics.
     * @param counters   counter metrics.
     * @param histograms histogram metrics.
     * @param meters     meter metrics.
     * @param timers     timer metrics.
     */
    public void report(SortedMap<String, Gauge> gauges,
                       SortedMap<String, Counter> counters,
                       SortedMap<String, Histogram> histograms,
                       SortedMap<String, Meter> meters,
                       SortedMap<String, Timer> timers) {

        // Just an estimate to reduce resizing.
        List<MetricDatum> data = new ArrayList<MetricDatum>(
                gauges.size() + counters.size() + meters.size() + 2 * histograms.size() + 2 * timers.size()
        );

        // Translate various metric classes to MetricDatum
        for (Map.Entry<String, Gauge> gaugeEntry : gauges.entrySet()) {
            reportGauge(gaugeEntry, typeDimValGauge, data);
        }
        for (Map.Entry<String, Counter> counterEntry : counters.entrySet()) {
            reportCounter(counterEntry, typeDimValCounterCount, data);
        }
        for (Map.Entry<String, Meter> meterEntry : meters.entrySet()) {
            reportCounter(meterEntry, typeDimValMeterCount, data);
        }
        for (Map.Entry<String, Histogram> histogramEntry : histograms.entrySet()) {
            reportCounter(histogramEntry, typeDimValHistoSamples, data);
            reportSampling(histogramEntry, typeDimValHistoStats, 1.0, data);
        }
        for (Map.Entry<String, Timer> timerEntry : timers.entrySet()) {
            reportCounter(timerEntry, typeDimValTimerSamples, data);
            reportSampling(timerEntry, typeDimValTimerStats, 0.000001, data); // nanos -> millis
        }

        // Filter out unreportable entries.
        Collection<MetricDatum> nonEmptyData = Collections2.filter(data, new Predicate<MetricDatum>() {
            @Override
            public boolean apply(MetricDatum input) {
                if (input == null) {
                    return false;
                } else if (input.getStatisticValues() != null) {
                    // CloudWatch rejects any Statistic Sets with sample count == 0, which it probably should reject.
                    return input.getStatisticValues().getSampleCount() > 0;
                }
                return true;
            }
        });

        // Whether to use local "now" (true, new Date()) or cloudwatch service "now" (false, leave null).
        if (timestampLocal) {
            Date now = new Date();
            for (MetricDatum datum : nonEmptyData) {
                datum.withTimestamp(now);
            }
        }

        // Finally, apply any user-level filter.
        Collection<MetricDatum> filtered = Collections2.filter(nonEmptyData, reporterFilter);

        // Each CloudWatch API request may contain at maximum 20 datums. Break into partitions of 20.
        Iterable<List<MetricDatum>> dataPartitions = Iterables.partition(filtered, 20);
        List<Future<?>> cloudWatchFutures = Lists.newArrayListWithExpectedSize(filtered.size());

        // Submit asynchronously with threads.
        for (List<MetricDatum> dataSubset : dataPartitions) {
            cloudWatchFutures.add(cloudWatch.putMetricDataAsync(new PutMetricDataRequest()
                    .withNamespace(metricNamespace)
                    .withMetricData(dataSubset)));
        }

        // Wait for CloudWatch putMetricData futures to be fulfilled.
        for (Future<?> cloudWatchFuture : cloudWatchFutures) {
            try {
                cloudWatchFuture.get();
            } catch (Exception e) {
                LOG.error("Exception reporting metrics to CloudWatch. Some or all of the data in this CloudWatch API request " +
                        "may have been discarded, did not make it to CloudWatch.", e);
            }
        }

        LOG.debug("Sent {} metric data to CloudWatch. namespace: {}", filtered.size(), metricNamespace);
    }


    void reportGauge(Map.Entry<String, Gauge> gaugeEntry, String typeDimValue, List<MetricDatum> data) {
        Gauge gauge = gaugeEntry.getValue();

        Object valueObj = gauge.getValue();
        if (valueObj == null) {
            return;
        }

        String valueStr = valueObj.toString();
        if (NumberUtils.isNumber(valueStr)) {
            final Number value = NumberUtils.createNumber(valueStr);

            DemuxedKey key = new DemuxedKey(appendGlobalDimensions(gaugeEntry.getKey()));
            Iterables.addAll(data, key.newDatums(typeDimName, typeDimValue, new Function<MetricDatum, MetricDatum>() {
                @Override
                public MetricDatum apply(MetricDatum datum) {
                    return datum.withValue(value.doubleValue());
                }
            }));
        }
    }

    void reportCounter(Map.Entry<String, ? extends Counting> entry, String typeDimValue, List<MetricDatum> data) {
        Counting metric = entry.getValue();
        final long diff = diffLast(metric);
        if (diff == 0) {
            // Don't submit metrics that have not changed. No reason to keep these alive. Also saves on CloudWatch
            // costs.
            return;
        }

        DemuxedKey key = new DemuxedKey(appendGlobalDimensions(entry.getKey()));
        Iterables.addAll(data, key.newDatums(typeDimName, typeDimValue, new Function<MetricDatum, MetricDatum>() {
            @Override
            public MetricDatum apply(MetricDatum datum) {
                return datum.withValue((double) diff).withUnit(StandardUnit.Count);
            }
        }));
    }

    /**
     * @param rescale the submitted sum by this multiplier. 1.0 is the identity (no rescale).
     */
    void reportSampling(Map.Entry<String, ? extends Sampling> entry, String typeDimValue, double rescale, List<MetricDatum> data) {
        Sampling metric = entry.getValue();
        Snapshot snapshot = metric.getSnapshot();
        double scaledSum = sum(snapshot.getValues()) * rescale;
        final StatisticSet statisticSet = new StatisticSet()
                .withSum(scaledSum)
                .withSampleCount((double) snapshot.size())
                .withMinimum((double) snapshot.getMin() * rescale)
                .withMaximum((double) snapshot.getMax() * rescale);

        DemuxedKey key = new DemuxedKey(appendGlobalDimensions(entry.getKey()));
        Iterables.addAll(data, key.newDatums(typeDimName, typeDimValue, new Function<MetricDatum, MetricDatum>() {
            @Override
            public MetricDatum apply(MetricDatum datum) {
                return datum.withStatisticValues(statisticSet);
            }
        }));
    }

    private long diffLast(Counting metric) {
        long count = metric.getCount();

        Long lastCount = lastPolledCounts.get(metric);
        lastPolledCounts.put(metric, count);

        if (lastCount == null) {
            lastCount = 0L;
        }
        return count - lastCount;
    }

    private long sum(long[] values) {
        long sum = 0L;
        for (long value : values) sum += value;
        return sum;
    }

    private String appendGlobalDimensions(String metric) {
        if (StringUtils.isBlank(StringUtils.trim(dimensions))) {
            return metric;
        } else {
            return metric + Constants.NAME_TOKEN_DELIMITER + dimensions;
        }
    }

}
