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

package dk.trustworks.essentials.examples.perflab;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "essentials.lab")
public class EssentialsPerformanceLabProperties {

    private Mode mode = Mode.SHOWCASE;
    private String scenario = "catalog";
    private Duration warmup = Duration.ofSeconds(10);
    private Duration duration = Duration.ofSeconds(30);
    private int producerThreads = 4;
    private int subscriberCount = 5;
    private int queueCount = 10;
    private int aggregateCardinality = 1_000;
    private long randomSeed = 42L;
    private int appendMaxAttempts = 3;
    private Duration appendRetryBackoff = Duration.ofMillis(2);
    private String metricsOutputFile;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String scenario) {
        this.scenario = scenario;
    }

    public Duration getWarmup() {
        return warmup;
    }

    public void setWarmup(Duration warmup) {
        this.warmup = warmup;
    }

    public Duration getDuration() {
        return duration;
    }

    public void setDuration(Duration duration) {
        this.duration = duration;
    }

    public int getProducerThreads() {
        return producerThreads;
    }

    public void setProducerThreads(int producerThreads) {
        this.producerThreads = producerThreads;
    }

    public int getSubscriberCount() {
        return subscriberCount;
    }

    public void setSubscriberCount(int subscriberCount) {
        this.subscriberCount = subscriberCount;
    }

    public int getQueueCount() {
        return queueCount;
    }

    public void setQueueCount(int queueCount) {
        this.queueCount = queueCount;
    }

    public int getAggregateCardinality() {
        return aggregateCardinality;
    }

    public void setAggregateCardinality(int aggregateCardinality) {
        this.aggregateCardinality = aggregateCardinality;
    }

    public long getRandomSeed() {
        return randomSeed;
    }

    public void setRandomSeed(long randomSeed) {
        this.randomSeed = randomSeed;
    }

    public int getAppendMaxAttempts() {
        return appendMaxAttempts;
    }

    public void setAppendMaxAttempts(int appendMaxAttempts) {
        this.appendMaxAttempts = appendMaxAttempts;
    }

    public Duration getAppendRetryBackoff() {
        return appendRetryBackoff;
    }

    public void setAppendRetryBackoff(Duration appendRetryBackoff) {
        this.appendRetryBackoff = appendRetryBackoff;
    }

    public String getMetricsOutputFile() {
        return metricsOutputFile;
    }

    public void setMetricsOutputFile(String metricsOutputFile) {
        this.metricsOutputFile = metricsOutputFile;
    }

    public enum Mode {
        SHOWCASE,
        BENCHMARK
    }
}
