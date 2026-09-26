/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.examples.perflab.harness;

import java.lang.management.ManagementFactory;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * JVM-side counters captured either side of a measurement window.
 * <p>
 * Garbage collection is the usual explanation for a p99 that will not come down while p50 looks
 * healthy, so a latency result without GC numbers beside it cannot be interpreted. Heap growth is
 * captured for the same reason a queue design needs it: a fetcher that buffers unboundedly reports
 * excellent throughput right up to the point it does not.
 */
public final class JvmSnapshot {
    private final long gcCount;
    private final long gcTimeMillis;
    private final long heapUsedBytes;
    private final int  threadCount;

    private JvmSnapshot(long gcCount, long gcTimeMillis, long heapUsedBytes, int threadCount) {
        this.gcCount = gcCount;
        this.gcTimeMillis = gcTimeMillis;
        this.heapUsedBytes = heapUsedBytes;
        this.threadCount = threadCount;
    }

    public static JvmSnapshot capture() {
        var gcCount = 0L;
        var gcTimeMillis = 0L;
        for (var garbageCollector : ManagementFactory.getGarbageCollectorMXBeans()) {
            var collections = garbageCollector.getCollectionCount();
            var time = garbageCollector.getCollectionTime();
            // -1 means the collector does not report the figure; do not fold it in as a negative.
            gcCount += collections < 0 ? 0 : collections;
            gcTimeMillis += time < 0 ? 0 : time;
        }
        return new JvmSnapshot(gcCount,
                               gcTimeMillis,
                               ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                               ManagementFactory.getThreadMXBean().getThreadCount());
    }

    public Map<String, Long> deltaFrom(JvmSnapshot before) {
        requireNonNull(before, "No before snapshot provided");
        var delta = new LinkedHashMap<String, Long>();
        delta.put("jvm.gcCount", gcCount - before.gcCount);
        delta.put("jvm.gcTimeMillis", gcTimeMillis - before.gcTimeMillis);
        delta.put("jvm.heapUsedBytesChange", heapUsedBytes - before.heapUsedBytes);
        delta.put("jvm.heapUsedBytesAtEnd", heapUsedBytes);
        delta.put("jvm.threadCountChange", (long) (threadCount - before.threadCount));
        return delta;
    }
}
