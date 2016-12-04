package com.blacklocus.metrics;

import java.util.SortedMap;
import java.util.concurrent.TimeUnit;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.ScheduledReporter;
import com.codahale.metrics.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ScheduledReporter subclass for periodically publishing metrics to CloudWatch.
 */
public class ScheduledCloudWatchReporter extends ScheduledReporter {

    private static final Logger LOG = LoggerFactory.getLogger(CloudWatchReporter.class);

    private final CloudWatchReporter reporter;

    /**
     * Creates a new {@link ScheduledReporter} instance. The reporter does not report metrics until
     * {@link #start(long, TimeUnit)}.
     *
     * @param registry the {@link MetricRegistry} containing the metrics this reporter will report
     * @param reporter reporter
     */
    public ScheduledCloudWatchReporter(MetricRegistry registry,
                                       CloudWatchReporter reporter) {
        this(registry, MetricFilter.ALL, reporter);
    }

    /**
     * Creates a new {@link ScheduledReporter} instance. The reporter does not report metrics until
     * {@link #start(long, TimeUnit)}.
     *
     * @param registry     the {@link MetricRegistry} containing the metrics this reporter will report
     * @param metricFilter see {@link MetricFilter}
     * @param reporter     reporter
     */
    public ScheduledCloudWatchReporter(MetricRegistry registry,
                                       MetricFilter metricFilter,
                                       CloudWatchReporter reporter) {
        super(registry, "CloudWatchReporter:" + reporter.getMetricNamespace(), metricFilter, TimeUnit.MINUTES, TimeUnit.MINUTES);
        this.reporter = reporter;
    }

    @Override
    public void report(SortedMap<String, Gauge> gauges,
                       SortedMap<String, Counter> counters,
                       SortedMap<String, Histogram> histograms,
                       SortedMap<String, Meter> meters,
                       SortedMap<String, Timer> timers) {
        // We can't let an exception leak out of here, or else the reporter will cease running as described in
        // java.util.concurrent.ScheduledExecutorService.scheduleAtFixedRate(Runnable, long, long, TimeUnit unit)
        try {
            reporter.report(gauges, counters, histograms, meters, timers);
        } catch (Exception e) {
            LOG.error("Error occurred while reporting metrics to CloudWatch.", e);
        }
    }
}
