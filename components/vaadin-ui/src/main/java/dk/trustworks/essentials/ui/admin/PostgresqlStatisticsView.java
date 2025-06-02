/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.ui.admin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.*;
import com.vaadin.flow.spring.annotation.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api.PostgresqlEventStoreStatisticsApi;
import dk.trustworks.essentials.components.foundation.postgresql.api.*;
import dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser;
import dk.trustworks.essentials.ui.view.*;
import jakarta.annotation.security.PermitAll;

import java.util.Map;

/**
 * A view for displaying PostgreSQL statistics, including table size, activity, cache hit ratio,
 * and slow queries. This view is part of the administrative UI and provides a user interface
 * for monitoring various aspects of a PostgreSQL database.
 * <p>
 * The available statistics include:
 * - Table Size Statistics: Displays the total size, table size, and index size of each table.
 * - Table Activity Statistics: Displays sequential scans, tuples read, index scans, and other
 *   activity metrics for each table.
 * - Table Cache Hit Ratio: Displays the cache hit ratio for each table.
 * - Slow Queries: Displays the top ten slowest queries based on total time, mean time, and
 *   execution frequency.
 */
@UIScope
@PermitAll
@SpringComponent
@Route(value = "postgresql", layout = AdminMainLayout.class)
public class PostgresqlStatisticsView extends VerticalLayout implements BeforeEnterObserver {

    private static final int TRUNCATE_SLOW_QUERY_AT_LENGTH = 25;

    private final EssentialsAuthenticatedUser                         authenticatedUser;
    private final PostgresqlEventStoreStatisticsApi                   postgresqlEventStoreStatisticsApi;
    private final PostgresqlQueryStatisticsApi                        postgresqlQueryStatisticsApi;

    private final Grid<Map.Entry<String, ApiTableSizeStatistics>>     sizeGrid;
    private final Grid<Map.Entry<String, ApiTableActivityStatistics>> activityGrid;
    private final Grid<Map.Entry<String, ApiTableCacheHitRatio>>      cacheGrid;
    private final Grid<ApiQueryStatistics>                            slowQueryGrid;

    public PostgresqlStatisticsView(EssentialsAuthenticatedUser authenticatedUser,
                                    PostgresqlEventStoreStatisticsApi postgresqlEventStoreStatisticsApi,
                                    PostgresqlQueryStatisticsApi postgresqlQueryStatisticsApi) {

        this.authenticatedUser = authenticatedUser;
        this.postgresqlEventStoreStatisticsApi = postgresqlEventStoreStatisticsApi;
        this.postgresqlQueryStatisticsApi = postgresqlQueryStatisticsApi;

        sizeGrid = createSizeGrid();
        activityGrid = createActivityGrid();
        cacheGrid = createCacheGrid();
        slowQueryGrid = createSlowQueryGrid();

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        H3 title = new H3("Postgresql statistics");
        Button refreshButton = new Button("Refresh", event -> loadData());
        add(title, refreshButton);

        loadData();

        add(new H4("Table Size Statistics"), sizeGrid);
        add(new H4("Table Activity Statistics"), activityGrid);
        add(new H4("Table Cache Hit Ratio"), cacheGrid);
        add(new H4("Slow queries"), slowQueryGrid);
    }

    private void loadData() {
        var sizeStats = postgresqlEventStoreStatisticsApi.fetchTableSizeStatistics(authenticatedUser.getPrincipal()).entrySet();
        sizeGrid.setItems(sizeStats);

        var activityStats = postgresqlEventStoreStatisticsApi.fetchTableActivityStatistics(authenticatedUser.getPrincipal()).entrySet();
        activityGrid.setItems(activityStats);

        var cacheStats = postgresqlEventStoreStatisticsApi.fetchTableCacheHitRatio(authenticatedUser.getPrincipal()).entrySet();
        cacheGrid.setItems(cacheStats);

        var slowQueries = postgresqlQueryStatisticsApi.getTopTenSlowestQueries(authenticatedUser.getPrincipal());
        slowQueryGrid.setItems(slowQueries);
    }

    private Grid<Map.Entry<String, ApiTableSizeStatistics>> createSizeGrid() {
        Grid<Map.Entry<String, ApiTableSizeStatistics>> grid = new Grid<>();
        grid.addColumn(Map.Entry::getKey).setHeader("Table Name").setAutoWidth(true);
        grid.addColumn(e -> e.getValue().totalSize()).setHeader("Total Size").setAutoWidth(true);
        grid.addColumn(e -> e.getValue().tableSize()).setHeader("Table Size").setAutoWidth(true);
        grid.addColumn(e -> e.getValue().indexSize()).setHeader("Index Size").setAutoWidth(true);
        grid.setAllRowsVisible(true);
        grid.setWidthFull();
        return grid;
    }

    private Grid<Map.Entry<String, ApiTableActivityStatistics>> createActivityGrid() {
        Grid<Map.Entry<String, ApiTableActivityStatistics>> grid = new Grid<>();
        grid.addColumn(Map.Entry::getKey).setHeader("Table Name").setAutoWidth(true);
        grid.addColumn(e -> e.getValue().seq_scan()).setHeader("Seq Scan").setAutoWidth(true);
        grid.addColumn(e -> e.getValue().seq_tup_read()).setHeader("Seq Tuples Read").setAutoWidth(true);
        grid.addColumn(e -> e.getValue().idx_scan()).setHeader("Idx Scan").setAutoWidth(true);
        grid.addColumn(e -> e.getValue().idx_tup_fetch()).setHeader("Idx Tuples Fetch").setAutoWidth(true);
        grid.addColumn(e -> e.getValue().n_tup_ins()).setHeader("Tuples Inserted").setAutoWidth(true);
        grid.addColumn(e -> e.getValue().n_tup_upd()).setHeader("Tuples Updated").setAutoWidth(true);
        grid.addColumn(e -> e.getValue().n_tup_del()).setHeader("Tuples Deleted").setAutoWidth(true);
        grid.setAllRowsVisible(true);
        grid.setWidthFull();
        return grid;
    }

    private Grid<Map.Entry<String, ApiTableCacheHitRatio>> createCacheGrid() {
        Grid<Map.Entry<String, ApiTableCacheHitRatio>> grid = new Grid<>();
        grid.addColumn(Map.Entry::getKey).setHeader("Table Name").setAutoWidth(true);
        grid.addColumn(e -> e.getValue().cacheHitRatio()).setHeader("Cache Hit Ratio (%)").setAutoWidth(true);
        grid.setAllRowsVisible(true);
        grid.setWidthFull();
        return grid;
    }

    private Grid<ApiQueryStatistics> createSlowQueryGrid() {
        Grid<ApiQueryStatistics> grid = new Grid<>();
        grid.addColumn(qs -> truncateSlowQuery(qs.query())).setHeader("Query").setAutoWidth(true);
        grid.addColumn(ApiQueryStatistics::totalTime).setHeader("Total Time (ms)").setAutoWidth(true);
        grid.addColumn(ApiQueryStatistics::meanTime).setHeader("Mean Time (ms)").setAutoWidth(true);
        grid.addColumn(ApiQueryStatistics::calls).setHeader("Calls").setAutoWidth(true);
        grid.setAllRowsVisible(true);
        grid.setWidthFull();
        return grid;
    }

    private String truncateSlowQuery(String slowQuery) {
        return slowQuery != null && slowQuery.length() > TRUNCATE_SLOW_QUERY_AT_LENGTH
               ? slowQuery.substring(0, TRUNCATE_SLOW_QUERY_AT_LENGTH) + "..."
               : slowQuery;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (!authenticatedUser.hasPostgresqlStatsReaderRole() && !authenticatedUser.hasAdminRole()) {
            event.forwardTo(AccessDeniedView.class);
        }
    }
}
