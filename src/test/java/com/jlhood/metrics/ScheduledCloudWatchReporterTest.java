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

package com.jlhood.metrics;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsync;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsyncClient;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;

import org.junit.Ignore;
import org.junit.Test;

public class ScheduledCloudWatchReporterTest {

    @Test
    @Ignore("ad-hoc usage")
    public void createTestData() throws InterruptedException {

        final ExecutorService executors = Executors.newCachedThreadPool();
        final AmazonCloudWatchAsync cloudWatch = new AmazonCloudWatchAsyncClient();

        // Publish metrics to us-west-2 (Oregon) region
        cloudWatch.setRegion(Region.getRegion(Regions.US_WEST_2));

        // increments the counter by 1 every second, meter ticked once, histogram ticked once, gauge given 1
        executors.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {

                MetricRegistry metricRegistry = new MetricRegistry();
                new ScheduledCloudWatchReporter(
                        metricRegistry,
                        new CloudWatchReporter(
                                ScheduledCloudWatchReporterTest.class.getSimpleName(),
                                cloudWatch)
                                .withDimensions("unit=test group=first"))
                        .start(1, TimeUnit.MINUTES);

                metricRegistry.register("TheGauge", new Gauge<Long>() {
                    @Override
                    public Long getValue() {
                        return 1L;
                    }
                });
                // Should be ignored by reporter
                metricRegistry.register("TheGauge notNumeric", new Gauge<String>() {
                    @Override
                    public String getValue() {
                        return "yellow";
                    }
                });

                while (!Thread.interrupted()) {
                    metricRegistry.counter("TheCounter TestDim=Yellow TestToken* machine=number1*").inc(1);
                    metricRegistry.meter("TheMeter").mark();
                    metricRegistry.histogram("TheHistogram").update(1);
                    metricRegistry.histogram("TheHistogram").update(1);
                    Timer.Context theTimer = metricRegistry.timer("TheTimer").time();
                    Thread.sleep(1000);
                    theTimer.close();
                }
                return null;
            }
        });

        // increments the counter by 2 every second, meter ticked twice, histogram ticked twice, gauge given 2
        executors.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {

                MetricRegistry metricRegistry = new MetricRegistry();
                new ScheduledCloudWatchReporter(
                        metricRegistry,
                        new CloudWatchReporter(
                                ScheduledCloudWatchReporterTest.class.getSimpleName(),
                                cloudWatch)
                                .withDimensions("unit=test group=second"))
                        .start(1, TimeUnit.MINUTES);

                metricRegistry.register("TheGauge", new Gauge<Long>() {
                    @Override
                    public Long getValue() {
                        return 2L;
                    }
                });
                // Should be ignored by reporter
                metricRegistry.register("TheGauge notNumeric", new Gauge<String>() {
                    @Override
                    public String getValue() {
                        return "green";
                    }
                });

                while (!Thread.interrupted()) {
                    metricRegistry.counter("TheCounter TestDim=Yellow").inc(2);
                    metricRegistry.meter("TheMeter").mark(3);
                    metricRegistry.histogram("TheHistogram").update(2);
                    Timer.Context theTimer = metricRegistry.timer("TheTimer").time();
                    Thread.sleep(500);
                    theTimer.close();
                    theTimer = metricRegistry.timer("TheTimer").time();
                    Thread.sleep(500);
                    theTimer.close();
                }
                return null;
            }
        });

        for (int i = 0; i < 60 /* one hour */; i++) {
            System.out.printf("Sleeping... %d minutes elapsed%n", i);
            Thread.sleep(60 * 1000);
        }
        executors.shutdownNow();
        executors.awaitTermination(5, TimeUnit.SECONDS);

    }
}
