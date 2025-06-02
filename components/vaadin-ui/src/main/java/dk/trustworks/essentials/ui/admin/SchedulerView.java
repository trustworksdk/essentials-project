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

import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.*;
import com.vaadin.flow.spring.annotation.*;
import dk.trustworks.essentials.ui.view.*;
import jakarta.annotation.security.PermitAll;


@UIScope
@PermitAll
@SpringComponent
@Route(value = "scheduler", layout = AdminMainLayout.class)
public class SchedulerView extends VerticalLayout implements BeforeEnterObserver {

    // TODO: when scheduler is added

    /*
    private final SchedulerApi                schedulerApi;
    private final EssentialsAuthenticatedUser authenticatedUser;

    private final Grid<ApiPgCronJob> pgCronJobGrid;
    private final Grid<ApiPgCronJobRunDetails> pgCronJobDetailsGrid;
    private final Grid<ApiScheduledJob> scheduledJobGrid;

    private final TextField pgCronJobRunField = new TextField("Search PgCron Job Runs");
    private final Button refreshButton = new Button("Refresh");

    private DataProvider<ApiPgCronJob, Void> pgCronJobProvider;
    private DataProvider<ApiScheduledJob, Void> scheduledJobProvider;
    private ConfigurableFilterDataProvider<ApiPgCronJobRunDetails, Void, String> pgCronJobDetailsProvider;

    public SchedulerView(SchedulerApi schedulerApi, EssentialsAuthenticatedUser authenticatedUser) {
        this.schedulerApi = schedulerApi;
        this.authenticatedUser = authenticatedUser;

        pgCronJobGrid = createPgCronJobGrid();
        setPgCronJobProvider();
        scheduledJobGrid = createScheduledJobGrid();
        setScheduledJobProvider();
        pgCronJobDetailsGrid = createPgCronJobDetailsGrid();
        setPgCronJobDetailsProvider();

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        H3 title = new H3("Scheduler administration");
        refreshButton.addClickListener(click -> {
            pgCronJobRunField.clear();
            pgCronJobProvider.refreshAll();
            scheduledJobProvider.refreshAll();
        });

        add(title, refreshButton);

        add(new H4("Pg Cron Jobs"), pgCronJobGrid);
        add(new H4("Scheduled Executor Jobs"), scheduledJobGrid);
        add(pgCronJobRunField);
        add(new H4("Pg Cron Job Run Details"), pgCronJobDetailsGrid);
    }

    private void setPgCronJobProvider() {
        pgCronJobProvider = DataProvider.<ApiPgCronJob>fromCallbacks(
                query -> {
                    int offset = query.getOffset();
                    int limit = query.getLimit();
                    var pgCronJobs = schedulerApi.getPgCronJobs(authenticatedUser.getPrincipal(), offset, limit);
                    return pgCronJobs.stream();
                },
                query -> {
                    return (int) schedulerApi.getTotalPgCronJobs(authenticatedUser.getPrincipal());
                }
        );
        pgCronJobGrid.setDataProvider(pgCronJobProvider);
    }

    private void setScheduledJobProvider() {
        scheduledJobProvider = DataProvider.<ApiScheduledJob>fromCallbacks(
                query -> {
                    int offset = query.getOffset();
                    int limit = query.getLimit();
                    var schedulerJobs = schedulerApi.getExecutorJobs(authenticatedUser.getPrincipal(), offset, limit);
                    return schedulerJobs.stream();
                },
                query -> {
                    return (int) schedulerApi.getTotalExecutorJobs(authenticatedUser.getPrincipal());
                }
        );
        scheduledJobGrid.setDataProvider(scheduledJobProvider);
    }

    private void setPgCronJobDetailsProvider() {
        pgCronJobDetailsProvider = DataProvider.<ApiPgCronJobRunDetails, String>fromFilteringCallbacks(
                query -> {
                    String filter = query.getFilter().orElse(null);
                    int offset = query.getOffset();
                    int limit = query.getLimit();
                    if (filter != null) {
                        return schedulerApi.getPgCronJobRunDetails(authenticatedUser.getPrincipal(), Integer.valueOf(filter), offset, limit).stream();
                    }
                    return Stream.of();
                },
                query -> {
                    String filter = query.getFilter().orElse(null);
                    if (filter != null) {
                        return (int) schedulerApi.getTotalPgCronJobRunDetails(authenticatedUser.getPrincipal(), Integer.valueOf(filter));
                    }
                    return 0;
                }
        ).withConfigurableFilter();
        pgCronJobDetailsGrid.setDataProvider(pgCronJobDetailsProvider);

        pgCronJobRunField.addValueChangeListener(e -> {
            String searchTerm = e.getValue();
            pgCronJobDetailsProvider.setFilter(searchTerm);
        });
    }

    private Grid<ApiPgCronJob> createPgCronJobGrid() {
        Grid<ApiPgCronJob> grid = new Grid<>();
        grid.addColumn(ApiPgCronJob::jobId).setHeader("Job Id").setAutoWidth(true);
        grid.addColumn(ApiPgCronJob::schedule).setHeader("Schedule").setAutoWidth(true);
        grid.addColumn(ApiPgCronJob::command).setHeader("Command").setAutoWidth(true);
        grid.addColumn(ApiPgCronJob::active).setHeader("Active").setAutoWidth(true);
        grid.setAllRowsVisible(true);
        grid.setWidthFull();
        return grid;
    }

    private Grid<ApiScheduledJob> createScheduledJobGrid() {
        Grid<ApiScheduledJob> grid = new Grid<>();
        grid.addColumn(ApiScheduledJob::name).setHeader("Name").setAutoWidth(true);
        grid.addColumn(ApiScheduledJob::initialDelay).setHeader("Delay").setAutoWidth(true);
        grid.addColumn(ApiScheduledJob::period).setHeader("Period").setAutoWidth(true);
        grid.addColumn(ApiScheduledJob::unit).setHeader("Unit").setAutoWidth(true);
        grid.addColumn(ApiScheduledJob::scheduledAt).setHeader("Scheduled At").setAutoWidth(true);
        grid.setAllRowsVisible(true);
        grid.setWidthFull();
        return grid;
    }

    private Grid<ApiPgCronJobRunDetails> createPgCronJobDetailsGrid() {
        Grid<ApiPgCronJobRunDetails> grid = new Grid<>();
        grid.addColumn(ApiPgCronJobRunDetails::jobId).setHeader("Job Id").setAutoWidth(true);
        grid.addColumn(ApiPgCronJobRunDetails::runId).setHeader("Run Id").setAutoWidth(true);
        grid.addColumn(ApiPgCronJobRunDetails::command).setHeader("Command").setAutoWidth(true);
        grid.addColumn(ApiPgCronJobRunDetails::status).setHeader("Status").setAutoWidth(true);
        grid.addColumn(ApiPgCronJobRunDetails::returnMessage).setHeader("Message").setAutoWidth(true);
        grid.addColumn(ApiPgCronJobRunDetails::startTime).setHeader("Start").setAutoWidth(true);
        grid.addColumn(ApiPgCronJobRunDetails::endTime).setHeader("End").setAutoWidth(true);
        grid.setAllRowsVisible(true);
        grid.setWidthFull();
        return grid;
    }


    */

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
//        if (!authenticatedUser.hasSchedulerReaderRole && !authenticatedUser.hasAdminRole()) {
//            event.forwardTo(AccessDeniedView.class);
//        }
    }


}
