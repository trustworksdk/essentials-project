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
