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

import org.slf4j.*;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.*;

/**
 * Builds the benchmark lab's PostgreSQL container with an explicit, recorded resource budget.
 * <p>
 * Phase 0 found that measurements taken where the system saturates CPU swing by 50–240% between
 * repetitions. The cause is visible in the topology: {@code dockerd} runs <em>inside</em> this
 * devcontainer, so a Testcontainers PostgreSQL is a child of it and shares its cgroup budget — a
 * quota of eight CPUs, which both the JVM and the database size their thread pools against as if it
 * were the fourteen that {@code nproc} advertises. The load generator and the database then fight
 * for the same cores, and the interference is what makes the numbers bimodal.
 * <p>
 * <b>The lever is partitioning, not headroom.</b> The quota cannot be raised from inside the
 * container — that is a {@code devcontainer.json} change on the host. What can be done is to divide
 * the cores deterministically: give the database an exclusive CPU set and leave the rest to the
 * generator. Absolute throughput drops, because each side now has fewer cores than it was
 * contending for; but it stops varying, and a gate needs reproducibility far more than it needs a
 * big number.
 * <p>
 * <b>Pin both sides.</b> Constraining the database alone is not enough — an unpinned JVM will still
 * schedule onto the database's cores. A JVM cannot set its own CPU affinity, so the benchmark
 * invocation has to be wrapped:
 * <pre>{@code
 * # database on cores 4-7 (the default below), generator on 0-3
 * taskset -c 0-3 ./mvnw verify -pl examples/essentials-performance-lab \
 *     -Dit.test=DurableQueueBaselineProfilesIT -Dbenchmark.run=true \
 *     -Dlab.pg.cpuset=4-7
 * }</pre>
 * <p>
 * <b>What this does not affect.</b> WAL bytes per message and dead tuples per message — the metrics
 * the phase gates are written against — are counts of work, not rates. They do not move with CPU
 * budget or storage speed, which is a large part of why they were chosen. Resource configuration
 * matters for throughput and latency, and for making absolute numbers comparable across machines.
 */
public final class LabPostgres {
    private static final Logger log = LoggerFactory.getLogger(LabPostgres.class);

    public static final String IMAGE = "postgres:17.5-bookworm";

    private LabPostgres() {
    }

    /**
     * @param extraPostgresArgs appended to the {@code postgres} command, e.g. {@code wal_level=logical}
     */
    public static PostgreSQLContainer<?> create(String... extraPostgresArgs) {
        var cpuset = System.getProperty("lab.pg.cpuset", "");
        var cpus = Double.parseDouble(System.getProperty("lab.pg.cpus", "0"));
        var memoryMb = Long.parseLong(System.getProperty("lab.pg.memory-mb", "0"));
        var sharedBuffers = System.getProperty("lab.pg.shared-buffers", "");
        var tmpfsData = Boolean.parseBoolean(System.getProperty("lab.pg.tmpfs-data", "false"));

        var command = new ArrayList<String>();
        command.add("postgres");
        if (!sharedBuffers.isBlank()) {
            command.add("-c");
            command.add("shared_buffers=" + sharedBuffers);
        }
        for (var arg : extraPostgresArgs) {
            command.add("-c");
            command.add(arg);
        }

        var container = new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName("essentials_lab")
                .withUsername("essentials")
                .withPassword("essentials");
        container.setCommand(command.toArray(String[]::new));

        if (tmpfsData) {
            // Removes storage speed and its variance from the measurement entirely. Correct when
            // comparing two designs, wrong when quoting absolute latency — so it is opt-in and
            // recorded, never a default.
            container.withTmpFs(Map.of("/var/lib/postgresql/data", "rw,size=2g"));
        }

        container.withCreateContainerCmdModifier(cmd -> {
            var hostConfig = Objects.requireNonNull(cmd.getHostConfig());
            if (!cpuset.isBlank()) {
                hostConfig.withCpusetCpus(cpuset);
            }
            if (cpus > 0) {
                hostConfig.withCpuPeriod(100_000L).withCpuQuota((long) (cpus * 100_000L));
            }
            if (memoryMb > 0) {
                hostConfig.withMemory(memoryMb * 1024L * 1024L);
            }
        });

        log.info("Lab PostgreSQL: cpuset='{}' cpus={} memoryMb={} sharedBuffers='{}' tmpfsData={}",
                 cpuset.isBlank() ? "(unconstrained)" : cpuset,
                 cpus == 0 ? "(unconstrained)" : cpus,
                 memoryMb == 0 ? "(unconstrained)" : memoryMb,
                 sharedBuffers.isBlank() ? "(image default)" : sharedBuffers,
                 tmpfsData);
        return container;
    }

    /**
     * The configuration as applied, for the run's result JSON. A resource-constrained measurement
     * whose constraints were not recorded is not comparable with anything.
     */
    public static Map<String, String> describe() {
        var described = new LinkedHashMap<String, String>();
        described.put("lab.pg.cpuset", System.getProperty("lab.pg.cpuset", ""));
        described.put("lab.pg.cpus", System.getProperty("lab.pg.cpus", "0"));
        described.put("lab.pg.memoryMb", System.getProperty("lab.pg.memory-mb", "0"));
        described.put("lab.pg.sharedBuffers", System.getProperty("lab.pg.shared-buffers", ""));
        described.put("lab.pg.tmpfsData", System.getProperty("lab.pg.tmpfs-data", "false"));
        described.put("lab.generator.cpuAffinity", readSelfCpuAffinity());
        return described;
    }

    /**
     * The generator's own affinity, so a run that forgot its {@code taskset} wrapper is visible in
     * the result rather than quietly incomparable with the runs beside it.
     */
    private static String readSelfCpuAffinity() {
        try {
            var status = java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/self/status"));
            return status.stream()
                         .filter(line -> line.startsWith("Cpus_allowed_list:"))
                         .map(line -> line.substring("Cpus_allowed_list:".length()).trim())
                         .findFirst()
                         .orElse("unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }
}
