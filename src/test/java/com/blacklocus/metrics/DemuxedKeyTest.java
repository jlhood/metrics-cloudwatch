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

import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.MetricDatum;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.blacklocus.metrics.Constants.DEF_DIM_NAME_TYPE;

/**
 * @author Jason Dunkelberger (dirkraft)
 */
public class DemuxedKeyTest {

    @Test
    public void testSingleName() {
        DemuxedKey key = new DemuxedKey("SingleToken");
        List<MetricDatum> data = Lists.newArrayList(key.newDatums(DEF_DIM_NAME_TYPE, "test", new Function<MetricDatum, MetricDatum>() {
            @Override
            public MetricDatum apply(MetricDatum datum) {
                return datum.withValue(3.5);
            }
        }));
        Assert.assertEquals(1, data.size());
    }

    @Test
    public void testMultiName() {
        DemuxedKey key = new DemuxedKey("TokenOne Two");
        List<MetricDatum> data = Lists.newArrayList(key.newDatums(DEF_DIM_NAME_TYPE, "test", Functions.<MetricDatum>identity()));
        Assert.assertEquals(1, data.size());
    }

    @Test
    public void testDefaultDimension() {
        DemuxedKey key = new DemuxedKey("wheee");
        List<MetricDatum> data = Lists.newArrayList(key.newDatums(DEF_DIM_NAME_TYPE, "test", Functions.<MetricDatum>identity()));
        Assert.assertEquals(1, data.size());
        MetricDatum metricDatum = data.get(0);
        Assert.assertEquals(1, metricDatum.getDimensions().size());
        Dimension dimension = metricDatum.getDimensions().get(0);
        Assert.assertEquals(DEF_DIM_NAME_TYPE, dimension.getName());
        Assert.assertEquals("test", dimension.getValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testMultiDimension() {
        DemuxedKey key = new DemuxedKey("wheee color=orange token animal=okapi");
        List<MetricDatum> data = Lists.newArrayList(key.newDatums(DEF_DIM_NAME_TYPE, "test", Functions.<MetricDatum>identity()));
        Assert.assertEquals(1, data.size());
        MetricDatum metricDatum = data.get(0);
        Assert.assertEquals("wheee token", metricDatum.getMetricName());
        Assert.assertEquals(3, metricDatum.getDimensions().size());
        Assert.assertTrue(containsExactly(data, d(DEF_DIM_NAME_TYPE, "test", "color", "orange", "animal", "okapi")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testMixedNameAndDimensionPerm() {
        DemuxedKey key = new DemuxedKey("wheee* color=orange* token animal=okapi");
        List<MetricDatum> data = Lists.newArrayList(key.newDatums(DEF_DIM_NAME_TYPE, "pickle", Functions.<MetricDatum>identity()));
        Assert.assertEquals(4, data.size());
        Assert.assertTrue(containsExactly(data, "token", "wheee token"));
        Assert.assertTrue(containsExactly(data,
                d(DEF_DIM_NAME_TYPE, "pickle", "animal", "okapi"),
                d(DEF_DIM_NAME_TYPE, "pickle", "animal", "okapi", "color", "orange")));
    }

    @Test
    public void testMultiPermName() {
        DemuxedKey key = new DemuxedKey("One*");
        List<MetricDatum> data = Lists.newArrayList(key.newDatums(DEF_DIM_NAME_TYPE, "test", Functions.<MetricDatum>identity()));
        Assert.assertEquals(1, data.size());
        Assert.assertTrue(containsExactly(data, "One"));

        key = new DemuxedKey("Two option*");
        data = Lists.newArrayList(key.newDatums(DEF_DIM_NAME_TYPE, "test", Functions.<MetricDatum>identity()));
        Assert.assertEquals(2, data.size());
        // Note that token order must be maintained
        Assert.assertTrue(containsExactly(data, "Two", "Two option"));

        key = new DemuxedKey("Three double* option*");
        data = Lists.newArrayList(key.newDatums(DEF_DIM_NAME_TYPE, "test", Functions.<MetricDatum>identity()));
        Assert.assertEquals(4, data.size());
        Assert.assertTrue(containsExactly(data, "Three", "Three double", "Three option", "Three double option"));

        key = new DemuxedKey("All* keys* optional*");
        data = Lists.newArrayList(key.newDatums(DEF_DIM_NAME_TYPE, "test", Functions.<MetricDatum>identity()));
        Assert.assertEquals(7, data.size());
        Assert.assertTrue(containsExactly(data, "All", "keys", "optional", "All keys", "keys optional", "All optional", "All keys optional"));

        key = new DemuxedKey("Tail* required");
        data = Lists.newArrayList(key.newDatums(DEF_DIM_NAME_TYPE, "test", Functions.<MetricDatum>identity()));
        Assert.assertEquals(2, data.size());
        Assert.assertTrue(containsExactly(data, "Tail required", "required"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testMultiPermDimensions() {
        DemuxedKey key = new DemuxedKey("Name key=value* color=green* machine=localhost");
        List<MetricDatum> data = Lists.newArrayList(key.newDatums(DEF_DIM_NAME_TYPE, "testMultiPermDimensions", Functions.<MetricDatum>identity()));
        Assert.assertEquals(4, data.size());
        Assert.assertTrue(containsExactly(data, "Name"));
        Assert.assertTrue(containsExactly(data,
                d(DEF_DIM_NAME_TYPE, "testMultiPermDimensions", "machine", "localhost"),
                d(DEF_DIM_NAME_TYPE, "testMultiPermDimensions", "key", "value", "machine", "localhost"),
                d(DEF_DIM_NAME_TYPE, "testMultiPermDimensions", "color", "green", "machine", "localhost"),
                d(DEF_DIM_NAME_TYPE, "testMultiPermDimensions", "key", "value", "color", "green", "machine", "localhost")
        ));
    }

    boolean containsExactly(List<MetricDatum> data, String... names) {
        return Sets.symmetricDifference(Sets.newHashSet(Lists.transform(data, new Function<MetricDatum, String>() {
            @Override
            public String apply(MetricDatum input) {
                return input.getMetricName();
            }
        })), Sets.newHashSet(names)).isEmpty();
    }

    boolean containsExactly(List<MetricDatum> data, Set<Dimension>... dimensions) {
        return Sets.newHashSet(Lists.transform(data, new Function<MetricDatum, Set<Dimension>>() {
            @Override
            public Set<Dimension> apply(MetricDatum input) {
                return Sets.newHashSet(input.getDimensions());
            }
        })).equals(Sets.newHashSet(dimensions));
    }

    Set<Dimension> d(String... keyValuePairs) {
        Set<Dimension> dimensions = new HashSet<Dimension>(keyValuePairs.length / 2);
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            dimensions.add(new Dimension().withName(keyValuePairs[i]).withValue(keyValuePairs[i + 1]));
        }
        return dimensions;
    }
}
