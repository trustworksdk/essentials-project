package dk.trustworks.essentials.components.foundation.scheduler.executor;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.essentials.components.foundation.postgresql.TableChangeNotification;

import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;

public class ExecutorJobNotification extends TableChangeNotification {
    @JsonProperty("name")
    private String   name;
    @JsonProperty("initial_delay")
    private long     initialDelay;
    @JsonProperty("period")
    private long     period;
    @JsonProperty("time_unit")
    private String unit;
    @JsonProperty("scheduled_at")
    private OffsetDateTime scheduledAt;
}
