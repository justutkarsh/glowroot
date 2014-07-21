/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.trace;

import org.glowroot.api.MetricName;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.markers.ThreadSafe;
import org.glowroot.trace.model.CurrentTraceMetricHolder;
import org.glowroot.trace.model.MetricTimerExt;
import org.glowroot.trace.model.TraceMetric;
import org.glowroot.weaving.WeavingTimerService;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class WeavingTimerServiceImpl implements WeavingTimerService {

    private final TraceRegistry traceRegistry;
    private final MetricName metricName;

    WeavingTimerServiceImpl(TraceRegistry traceRegistry, MetricNameCache metricNameCache) {
        this.traceRegistry = traceRegistry;
        this.metricName = metricNameCache.getName(OnlyForTheMetricName.class);
    }

    @Override
    public WeavingTimer start() {
        CurrentTraceMetricHolder currentTraceMetricHolder =
                traceRegistry.getCurrentTraceMetricHolder();
        TraceMetric currentMetric = currentTraceMetricHolder.get();
        if (currentMetric == null) {
            return NopWeavingTimer.INSTANCE;
        }
        final MetricTimerExt metricTimer = currentMetric.startNestedMetric(metricName);
        return new WeavingTimer() {
            @Override
            public void stop() {
                metricTimer.stop();
            }
        };
    }

    @ThreadSafe
    private static class NopWeavingTimer implements WeavingTimer {
        private static final NopWeavingTimer INSTANCE = new NopWeavingTimer();
        @Override
        public void stop() {}
    }

    @Pointcut(className = "", methodName = "", metricName = "glowroot weaving")
    private static class OnlyForTheMetricName {}
}
