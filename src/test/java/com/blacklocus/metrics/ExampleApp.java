/**
 * Copyright 2013-2016 BlackLocus
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.blacklocus.metrics;

import java.util.List;
import java.util.concurrent.TimeUnit;

import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsyncClient;
import com.codahale.metrics.MetricRegistry;


class ExampleMetrics {

    private final MetricRegistry registry = new MetricRegistry();

    public ExampleMetrics() {
        new ScheduledCloudWatchReporter(
                registry,
                new CloudWatchReporter(
                        ExampleMetrics.class.getSimpleName(),
                        new AmazonCloudWatchAsyncClient()
                ))
                .start(1, TimeUnit.MINUTES);
    }

    public void sentThatThing() {
        registry.counter("sentThatThing").inc();
    }

    public void gotABatchOfThoseThingsYaSentMe(int count) {
        registry.counter("gotThatThing").inc(count);
    }
}

public class ExampleApp {

    private final ExampleMetrics exampleMetrics;

    public ExampleApp(ExampleMetrics exampleMetrics) {
        this.exampleMetrics = exampleMetrics;
    }

    public void sendAThing() {
        // ... somewhere in the code not so far away ...
        exampleMetrics.sentThatThing();
    }

    public void receiveSomeThings(List<Object> thoseThings) {
        exampleMetrics.gotABatchOfThoseThingsYaSentMe(thoseThings.size());
        // ... and so on ...
    }
}

