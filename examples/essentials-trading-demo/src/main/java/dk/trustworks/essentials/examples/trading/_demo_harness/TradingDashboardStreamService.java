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

package dk.trustworks.essentials.examples.trading._demo_harness;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small SSE broadcaster for live dashboard updates.
 */
@Service
public class TradingDashboardStreamService {
    private static final long MIN_BROADCAST_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    private final TradingDashboardQueryService queryService;
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final AtomicLong lastBroadcastNanos = new AtomicLong();

    public TradingDashboardStreamService(TradingDashboardQueryService queryService) {
        this.queryService = queryService;
    }

    public SseEmitter createEmitter() {
        var emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        sendSummary(emitter);
        return emitter;
    }

    public void broadcastSummary() {
        emitters.forEach(this::sendSummary);
    }

    public void broadcastSummaryThrottled() {
        var now = System.nanoTime();
        var previous = lastBroadcastNanos.get();
        if (now - previous < MIN_BROADCAST_INTERVAL_NANOS) {
            return;
        }
        if (!lastBroadcastNanos.compareAndSet(previous, now)) {
            return;
        }
        broadcastSummary();
    }

    private void sendSummary(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                                   .name("summary")
                                   .data(queryService.getSummary()));
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
            }
        }
    }
}
