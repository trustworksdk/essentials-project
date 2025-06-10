package dk.trustworks.essentials.components.foundation.scheduler.api;

import dk.trustworks.essentials.components.foundation.scheduler.EssentialsScheduler;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;

import java.util.List;

import static dk.trustworks.essentials.shared.security.EssentialsSecurityRoles.*;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityValidator.validateHasAnyEssentialsSecurityRoles;

public class DefaultSchedulerApi implements SchedulerApi {

    private final EssentialsScheduler        essentialsScheduler;
    private final EssentialsSecurityProvider securityProvider;

    public DefaultSchedulerApi(EssentialsScheduler essentialsScheduler,
                               EssentialsSecurityProvider securityProvider) {
        this.essentialsScheduler = essentialsScheduler;
        this.securityProvider = securityProvider;
    }

    private void validateRoles(Object principal) {
        validateHasAnyEssentialsSecurityRoles(securityProvider, principal, SCHEDULER_READER, ESSENTIALS_ADMIN);
    }

    @Override
    public List<ApiPgCronJob> getPgCronJobs(Object principal, long startIndex, long pageSize) {
        validateRoles(principal);
        return essentialsScheduler.fetchPgCronEntries(startIndex, pageSize).stream()
                                  .map(ApiPgCronJob::from)
                                  .toList();
    }

    @Override
    public long getTotalPgCronJobs(Object principal) {
        validateRoles(principal);
        return essentialsScheduler.getTotalPgCronEntries();
    }

    @Override
    public List<ApiPgCronJobRunDetails> getPgCronJobRunDetails(Object principal, Integer jobId, long startIndex, long pageSize) {
        validateRoles(principal);
        return essentialsScheduler.fetchPgCronJobRunDetails(jobId, startIndex, pageSize).stream()
                                  .map(ApiPgCronJobRunDetails::from)
                                  .toList();
    }

    @Override
    public long getTotalPgCronJobRunDetails(Object principal, Integer jobId) {
        validateRoles(principal);
        return essentialsScheduler.getTotalPgCronJobRunDetails(jobId);
    }

    @Override
    public List<ApiExecutorJob> getExecutorJobs(Object principal, long startIndex, long pageSize) {
        validateRoles(principal);
        return essentialsScheduler.fetchExecutorJobEntries(startIndex, pageSize).stream()
                                  .map(ApiExecutorJob::from)
                                  .toList();
    }

    @Override
    public long getTotalExecutorJobs(Object principal) {
        return essentialsScheduler.geTotalExecutorJobEntries();
    }
}
