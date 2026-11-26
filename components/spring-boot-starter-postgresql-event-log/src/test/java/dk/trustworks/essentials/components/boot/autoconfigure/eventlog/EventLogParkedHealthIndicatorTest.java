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
package dk.trustworks.essentials.components.boot.autoconfigure.eventlog;

import dk.trustworks.essentials.components.eventlog.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.health.contributor.Status;

import java.sql.SQLException;
import java.time.*;
import java.util.*;

import static dk.trustworks.essentials.components.boot.autoconfigure.eventlog.EventLogParkedHealthIndicator.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventLogParkedHealthIndicatorTest {
    private static final OffsetDateTime PARKED_AT  = OffsetDateTime.parse("2026-11-25T10:00:00Z");
    private static final OffsetDateTime RETRY_AT   = OffsetDateTime.parse("2026-11-25T10:05:00Z");
    private static final OffsetDateTime FAILED_AT  = OffsetDateTime.parse("2026-11-25T10:01:00Z");
    private static final ProgressKey    ORDERS     = ProgressKey.exclusive("OrdersView", "Orders");
    private static final ProgressKey    INVOICES   = new ProgressKey("InvoiceReactor", "Invoices", "node-a");

    private EventLogAdmin admin;

    @BeforeEach
    void setUp() {
        admin = mock(EventLogAdmin.class);
    }

    private static ParkedSummary retrying(ProgressKey key, long heads) {
        return new ParkedSummary(key, heads, 0, PARKED_AT, Optional.of(RETRY_AT), Optional.of(FAILED_AT), Optional.empty(), Optional.empty());
    }

    private static ParkedSummary terminal(ProgressKey key, long terminalHeads, long retryingHeads) {
        return new ParkedSummary(key, retryingHeads, terminalHeads, PARKED_AT, retryingHeads > 0 ? Optional.of(RETRY_AT) : Optional.empty(), Optional.of(FAILED_AT),
                                 Optional.of(ParkEventException.class.getName()), Optional.of("payload is not valid JSON"));
    }

    private EventLogParkedHealthIndicator indicator() {
        return new EventLogParkedHealthIndicator(admin, Status.DOWN, Duration.ZERO);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> subscriptions(org.springframework.boot.health.contributor.Health health) {
        return (List<Map<String, Object>>) health.getDetails().get(DETAIL_SUBSCRIPTIONS);
    }

    @Test
    void no_parked_head_is_up() throws SQLException {
        when(admin.parkedSummary()).thenReturn(List.of());

        var health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry(DETAIL_TOTAL_TERMINAL_HEADS, 0L)
                                       .containsEntry(DETAIL_TOTAL_RETRYING_HEADS, 0L)
                                       .containsEntry(DETAIL_TERMINAL_PARKED_STATUS, "DOWN");
        assertThat(subscriptions(health)).isEmpty();
    }

    @Test
    void only_retrying_heads_are_up_with_the_details() throws SQLException {
        // Retrying heads heal by themselves: no operator is needed, so the instance is healthy
        when(admin.parkedSummary()).thenReturn(List.of(retrying(ORDERS, 2), retrying(INVOICES, 1)));

        var health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry(DETAIL_TOTAL_TERMINAL_HEADS, 0L)
                                       .containsEntry(DETAIL_TOTAL_RETRYING_HEADS, 3L);
        assertThat(subscriptions(health)).hasSize(2);
        assertThat(subscriptions(health).get(0)).containsEntry(SUBSCRIPTION_SUBSCRIBER, "OrdersView")
                                                .containsEntry(SUBSCRIPTION_AGGREGATE_TYPE, "Orders")
                                                .containsEntry(SUBSCRIPTION_INSTANCE_NAME, "")
                                                .containsEntry(SUBSCRIPTION_TERMINAL_HEADS, 0L)
                                                .containsEntry(SUBSCRIPTION_RETRYING_HEADS, 2L)
                                                .containsEntry(SUBSCRIPTION_OLDEST_PARKED_AT, PARKED_AT.toString())
                                                .containsEntry(SUBSCRIPTION_NEXT_RETRY_AT, RETRY_AT.toString())
                                                .containsEntry(SUBSCRIPTION_LAST_FAILED_AT, FAILED_AT.toString())
                                                .doesNotContainKeys(SUBSCRIPTION_LAST_TERMINAL_ERROR_TYPE, SUBSCRIPTION_LAST_TERMINAL_ERROR_MESSAGE);
        assertThat(subscriptions(health).get(1)).containsEntry(SUBSCRIPTION_INSTANCE_NAME, "node-a");
    }

    @Test
    void a_terminal_head_reports_the_terminal_status_with_its_error() throws SQLException {
        when(admin.parkedSummary()).thenReturn(List.of(retrying(ORDERS, 1), terminal(INVOICES, 2, 1)));

        var health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry(DETAIL_TOTAL_TERMINAL_HEADS, 2L)
                                       .containsEntry(DETAIL_TOTAL_RETRYING_HEADS, 2L);
        assertThat(subscriptions(health).get(1)).containsEntry(SUBSCRIPTION_SUBSCRIBER, "InvoiceReactor")
                                                .containsEntry(SUBSCRIPTION_TERMINAL_HEADS, 2L)
                                                .containsEntry(SUBSCRIPTION_RETRYING_HEADS, 1L)
                                                .containsEntry(SUBSCRIPTION_LAST_TERMINAL_ERROR_TYPE, ParkEventException.class.getName())
                                                .containsEntry(SUBSCRIPTION_LAST_TERMINAL_ERROR_MESSAGE, "payload is not valid JSON");
    }

    @Test
    void the_terminal_status_is_configurable() throws SQLException {
        when(admin.parkedSummary()).thenReturn(List.of(terminal(ORDERS, 1, 0)));

        assertThat(new EventLogParkedHealthIndicator(admin, Status.OUT_OF_SERVICE, Duration.ZERO).health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        var custom = new EventLogParkedHealthIndicator(admin, new Status("PARKED"), Duration.ZERO).health();
        assertThat(custom.getStatus()).isEqualTo(new Status("PARKED"));
        assertThat(custom.getDetails()).containsEntry(DETAIL_TERMINAL_PARKED_STATUS, "PARKED");
    }

    @Test
    void a_failing_read_is_down_with_the_exception() throws SQLException {
        when(admin.parkedSummary()).thenThrow(new SQLException("connection refused"));

        var health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat((String) health.getDetails().get("error")).contains("SQLException", "connection refused");
    }

    @Test
    void the_result_is_reused_for_the_cache_ttl() throws SQLException {
        when(admin.parkedSummary()).thenReturn(List.of(retrying(ORDERS, 1)));
        var indicator = new EventLogParkedHealthIndicator(admin, Status.DOWN, Duration.ofMinutes(5));

        var first = indicator.health();
        indicator.health();
        indicator.health();

        // One statement per cache period, not one per probe
        verify(admin, times(1)).parkedSummary();
        assertThat(indicator.health()).isSameAs(first);
    }

    @Test
    void a_failure_is_cached_too() throws SQLException {
        when(admin.parkedSummary()).thenThrow(new SQLException("connection refused"));
        var indicator = new EventLogParkedHealthIndicator(admin, Status.DOWN, Duration.ofMinutes(5));

        indicator.health();
        indicator.health();

        verify(admin, times(1)).parkedSummary();
    }

    @Test
    void a_zero_cache_ttl_reads_every_time() throws SQLException {
        when(admin.parkedSummary()).thenReturn(List.of());
        var indicator = indicator();

        indicator.health();
        indicator.health();

        verify(admin, times(2)).parkedSummary();
    }

    @Test
    void a_negative_cache_ttl_is_refused() {
        assertThatThrownBy(() -> new EventLogParkedHealthIndicator(admin, Status.DOWN, Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}
