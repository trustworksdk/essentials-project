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

package dk.trustworks.essentials.components.foundation.scheduler.pgcron;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.essentials.components.foundation.postgresql.TableChangeNotification;

public class PgCronNotification extends TableChangeNotification {
    @JsonProperty("jobid")
    private Integer jobId;
    @JsonProperty("schedule")
    private String schedule;
    @JsonProperty("command")
    private String command;
    @JsonProperty("nodename")
    private String nodeName;
    @JsonProperty("nodeport")
    private Integer nodePort;
    @JsonProperty("database")
    private String database;
    @JsonProperty("active")
    private boolean active;
    @JsonProperty("jobname")
    private String jobName;

    public PgCronNotification() {}
}
