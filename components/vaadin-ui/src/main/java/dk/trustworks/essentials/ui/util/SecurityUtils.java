package dk.trustworks.essentials.ui.util;

import dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser;

public class SecurityUtils {

    private final EssentialsAuthenticatedUser authenticatedUser;

    public SecurityUtils(EssentialsAuthenticatedUser authenticatedUser) {
        this.authenticatedUser = authenticatedUser;
    }

    public boolean canAccessLocks() {
        return authenticatedUser.hasLockReaderRole() || authenticatedUser.hasAdminRole();
    }

    public boolean canWriteLocks() {
        return authenticatedUser.hasLockWriterRole() || authenticatedUser.hasAdminRole();
    }

    public boolean canAccessQueues() {
        return authenticatedUser.hasQueueReaderRole() || authenticatedUser.hasAdminRole();
    }

    public boolean canWriteQueues() {
        return authenticatedUser.hasQueueWriterRole() || authenticatedUser.hasAdminRole();
    }

    public boolean canAccessSubscriptions() {
        return authenticatedUser.hasSubscriptionReaderRole() || authenticatedUser.hasAdminRole();
    }

    public boolean canAccessPostgresqlStats() {
        return authenticatedUser.hasPostgresqlStatsReaderRole() || authenticatedUser.hasAdminRole();
    }

    public boolean canAccessScheduler() {
        return authenticatedUser.hasSchedulerReaderRole() || authenticatedUser.hasAdminRole();
    }
}
