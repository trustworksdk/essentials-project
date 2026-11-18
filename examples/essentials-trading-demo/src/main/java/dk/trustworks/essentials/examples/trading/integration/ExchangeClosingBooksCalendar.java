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

package dk.trustworks.essentials.examples.trading.integration;

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.examples.trading.integration.BusinessCalendar.BusinessDayClosedNotice;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.sql.Timestamp;
import java.time.*;
import java.util.Optional;
import java.util.concurrent.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The exchange's business days as the closing-books calendar the accounts roll on (EF-1, D22, D24, D32): the books of an
 * account roll at the exchange's close, not at midnight.
 * <p>
 * The closes live in {@code business_day_closed} ({@link BusinessCalendar}); this keeps an in-memory copy, because the
 * calendar is read on every mutating account command and must not query the database there. The copy is
 * <ul>
 *     <li>loaded whole when the application starts, before the accounts serve commands;</li>
 *     <li>updated at once on the instance that recorded a close ({@link BusinessDayClosedNotice}, after commit);</li>
 *     <li>polled every second for closes another instance recorded - so an instance can book a few changes into a day
 *     up to a second after another instance saw it close (decided in EF-1's Q9).</li>
 * </ul>
 * Active with the exchange only. Without it there is no business calendar and the accounts roll at midnight New York
 * ({@code AccountClosingBooksPolicy}).
 */
@Component
@ConditionalOnProperty(prefix = "trading-demo.simulators", name = {"exchange.base-url", "webhook-secret-file"})
public class ExchangeClosingBooksCalendar implements ClosingBooksBusinessCalendar {
    private static final Logger   log           = LoggerFactory.getLogger(ExchangeClosingBooksCalendar.class);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    /**
     * Closes are re-read this far back from the newest one seen, so a close another instance committed with an older
     * {@code received_at} is not missed. Recording a close twice is harmless.
     */
    private static final Duration POLL_OVERLAP  = Duration.ofSeconds(30);

    private final InMemoryClosingBooksBusinessCalendar calendar = new InMemoryClosingBooksBusinessCalendar();
    private final JdbcTemplate                         jdbcTemplate;
    private final BusinessCalendar                     businessCalendar;
    private final ScheduledExecutorService             polling;
    private volatile Instant                           newestReceivedAt = Instant.EPOCH;

    /**
     * @param businessCalendar the exchange's calendar; a dependency so that its table exists before it is read here
     */
    public ExchangeClosingBooksCalendar(BusinessCalendar businessCalendar, JdbcTemplate jdbcTemplate) {
        this.businessCalendar = requireNonNull(businessCalendar, "No businessCalendar provided");
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        poll();
        log.info("Accounts roll their books on the exchange's business days: {} closes loaded, open business date {}",
                 calendar.recordedCloses(), calendar.currentBusinessDate().map(LocalDate::toString).orElse("not known yet"));
        polling = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("closing-books-calendar").daemon().factory());
        polling.scheduleWithFixedDelay(this::pollQuietly, POLL_INTERVAL.toMillis(), POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Optional<LocalDate> currentBusinessDate() {
        return calendar.currentBusinessDate();
    }

    @Override
    public long businessDaysClosedBetween(LocalDate from, LocalDate to) {
        return calendar.businessDaysClosedBetween(from, to);
    }

    /**
     * The business date a new account opens in. Before the exchange has closed any day, the calendar does not know it;
     * then it is the exchange's own date in New York (its clock is virtual, so this application's clock cannot answer).
     *
     * @return the open business date, or empty if neither the calendar nor the exchange can tell
     */
    public Optional<LocalDate> openBusinessDate() {
        return calendar.currentBusinessDate()
                       .or(() -> businessCalendar.businessNow().map(now -> LocalDate.ofInstant(now, ZoneId.of("America/New_York"))));
    }

    /**
     * @return the last business day the exchange closed that this instance has seen
     */
    public Optional<LocalDate> latestClosedBusinessDate() {
        return calendar.latestClosedBusinessDate();
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void on(BusinessDayClosedNotice closed) {
        calendar.recordClose(closed.businessDate(), closed.nextBusinessDate());
    }

    @PreDestroy
    public void stop() {
        polling.shutdownNow();
    }

    private void pollQuietly() {
        try {
            poll();
        } catch (RuntimeException e) {
            log.warn("Cannot read the recorded business-day closes: {}", e.toString());
        }
    }

    private void poll() {
        var since = newestReceivedAt.minus(POLL_OVERLAP);
        jdbcTemplate.query("SELECT business_date, next_business_date, received_at FROM business_day_closed WHERE received_at > ?",
                           rs -> {
                               calendar.recordClose(rs.getObject("business_date", LocalDate.class), rs.getObject("next_business_date", LocalDate.class));
                               var receivedAt = rs.getTimestamp("received_at").toInstant();
                               if (receivedAt.isAfter(newestReceivedAt)) {
                                   newestReceivedAt = receivedAt;
                               }
                           },
                           Timestamp.from(since));
    }
}
