/*
 * Copyright (c) 2024 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.fluss.metrics.reporter;

import com.alibaba.fluss.annotation.PublicEvolving;
import com.alibaba.fluss.config.Configuration;
import com.alibaba.fluss.metrics.Metric;
import com.alibaba.fluss.metrics.groups.MetricGroup;

/**
 * Metric reporters are used to export {@link Metric Metrics} to an external backend.
 *
 * <p>Metric reporters are instantiated via a {@link MetricReporterPlugin}.
 *
 * @since 0.2
 */
@PublicEvolving
public interface MetricReporter {

    // ------------------------------------------------------------------------
    //  life cycle
    // ------------------------------------------------------------------------

    /**
     * Configures this reporter.
     *
     * <p>If the reporter was instantiated generically and hence parameter-less, this method is the
     * place where the reporter sets its basic fields based on configuration values. Otherwise, this
     * method will typically be a no-op since resources can be acquired in the constructor.
     *
     * <p>This method is always called first on a newly instantiated reporter.
     *
     * @param config A configuration object that contains all parameters configured for fluss.
     */
    void open(Configuration config);

    /** Closes this reporter. Should be used to close channels, streams and release resources. */
    void close();

    // ------------------------------------------------------------------------
    //  adding / removing metrics
    // ------------------------------------------------------------------------

    /**
     * Called when a new {@link Metric} was added.
     *
     * @param metric the metric that was added
     * @param metricName the name of the metric
     * @param group the group that contains the metric
     */
    void notifyOfAddedMetric(Metric metric, String metricName, MetricGroup group);

    /**
     * Called when a {@link Metric} was removed.
     *
     * @param metric the metric that should be removed
     * @param metricName the name of the metric
     * @param group the group that contains the metric
     */
    void notifyOfRemovedMetric(Metric metric, String metricName, MetricGroup group);
}
