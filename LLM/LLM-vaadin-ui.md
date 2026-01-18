# Vaadin UI - LLM Reference

> Token-efficient reference for LLMs. For detailed explanations, see [README](../components/vaadin-ui/README.md).

## Quick Facts
- **Base Package**: `dk.trustworks.essentials.ui`
- **Purpose**: Pre-built Vaadin admin UI for monitoring/managing Essentials components
- **Key Deps**: Vaadin Flow (provided), Spring Boot (provided)
- **Status**: WORK-IN-PROGRESS

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>vaadin-ui</artifactId>
</dependency>
```

**Dependencies from other modules**:
- `FencedLockManager`, `DurableQueues` from [foundation](./LLM-foundation.md)
- `EventStore`, `EventStoreSubscriptionManager` from [postgresql-event-store](./LLM-postgresql-event-store.md)
- Spring Boot Starters from [spring-boot-starter-modules](./LLM-spring-boot-starter-modules.md)

## TOC
- [Core Packages](#core-packages)
- [Admin Views](#admin-views)
- [Security](#security)
- [UI Components](#ui-components)
- [Integration Patterns](#integration-patterns)
- [Common Patterns](#common-patterns)
- [Gotchas](#gotchas)

## Core Packages

| Package | Contents |
|---------|----------|
| `dk.trustworks.essentials.ui.admin` | Admin views (locks, queues, subscriptions, stats, scheduler) |
| `dk.trustworks.essentials.ui.view` | Layout components (main layout, login, access denied) |
| `dk.trustworks.essentials.ui.component` | Reusable UI components (toolbar) |
| `dk.trustworks.essentials.ui.util` | Utilities (SecurityUtils) |

## Admin Views

Base package: `dk.trustworks.essentials.ui.admin` (except Login/AccessDenied in `.ui.view`)

| Route | Class | Required Bean | Required Role |
|-------|-------|---------------|---------------|
| `/locks` | `LocksView` | `DBFencedLockApi` | `LOCK_READER` |
| `/queues` | `QueuesView` | `DurableQueuesApi` | `QUEUE_READER` |
| `/subscriptions` | `SubscriptionsView` | `EventStoreApi` | `SUBSCRIPTION_READER` |
| `/eventprocessors` | `EventProcessorsView` | none | `SUBSCRIPTION_READER` |
| `/postgresql` | `PostgresqlStatisticsView` | `PostgresqlEventStoreStatisticsApi`, `PostgresqlQueryStatisticsApi` | `POSTGRESQL_STATS_READER` |
| `/scheduler` | `SchedulerView` | `SchedulerApi` | `SCHEDULER_READER` |
| `/login` | `AdminLoginView` | none | Public |
| `/access-denied` | `AccessDeniedView` | none | Public |

### Locks View Features
- View locks: name, token, instance ID, timestamps
- Release locks (requires `LOCK_WRITER`)

**Grid columns**: Lock Name, Current Token, Locked By, Lock Acquired, Last Confirmed, Actions

### Queues View Features
- Queue selector dropdown
- Statistics: total delivered, avg latency, first/last delivery
- Browse queued/dead-letter messages (paginated)
- Search by queue entry ID
- Actions (requires `QUEUE_WRITER`): Mark as dead-letter, Delete, Resurrect

**Message grid columns**: ID, Payload (truncated), Added Timestamp, Next Delivery, Total Attempts, Redelivery Attempts, Being Delivered, Actions

### Subscriptions View Features
- View subscriptions: subscriber ID, aggregate type, global order, last updated
- Load highest global event order (on-demand, performance impact)

### PostgreSQL Statistics View Metrics

| Section | Data |
|---------|------|
| Table Size | Total/table/index size per table |
| Table Activity | Sequential scans, tuples read, index scans, inserts/updates/deletes |
| Cache Hit Ratio | Hit percentage per table |
| Slow Queries | Top 10 by total time, mean time, call count |

### Scheduler View Features
- **PgCron Jobs**: ID, schedule (cron), command, active
- **Scheduled Executor Jobs**: Name, initial delay, period, time unit, scheduled-at
- **PgCron Run Details**: Job/run ID, command, status, return message, start/end timestamps

## Security

### EssentialsAuthenticatedUser Interface

**Location**: `dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser`

**Required methods**:
```java
Object getPrincipal();
boolean isAuthenticated();
boolean hasRole(String role);
Optional<String> getPrincipalName();
void logout();

// Role checks
boolean hasAdminRole();
boolean hasLockReaderRole();
boolean hasLockWriterRole();
boolean hasQueueReaderRole();
boolean hasQueuePayloadReaderRole();
boolean hasQueueWriterRole();
boolean hasSubscriptionReaderRole();
boolean hasSubscriptionWriterRole();
boolean hasPostgresqlStatsReaderRole();
boolean hasSchedulerReaderRole();
```

### Role Mapping

Base package: `dk.trustworks.essentials.shared.security`

| Enum | Role Name | Access |
|------|-----------|--------|
| `EssentialsSecurityRoles.ESSENTIALS_ADMIN` | `essentials_admin` | Full access (grants all permissions) |
| `EssentialsSecurityRoles.LOCK_READER` | `essentials_lock_reader` | View locks |
| `EssentialsSecurityRoles.LOCK_WRITER` | `essentials_lock_writer` | View + release locks |
| `EssentialsSecurityRoles.QUEUE_READER` | `essentials_queue_reader` | View queues + metadata |
| `EssentialsSecurityRoles.QUEUE_PAYLOAD_READER` | `essentials_queue_payload_reader` | View message payloads |
| `EssentialsSecurityRoles.QUEUE_WRITER` | `essentials_queue_writer` | Manage messages |
| `EssentialsSecurityRoles.SUBSCRIPTION_READER` | `essentials_subscription_reader` | View subscriptions |
| `EssentialsSecurityRoles.SUBSCRIPTION_WRITER` | `essentials_subscription_writer` | Manage subscriptions |
| `EssentialsSecurityRoles.POSTGRESQL_STATS_READER` | `essentials_postgresql_stats_reader` | View DB stats |
| `EssentialsSecurityRoles.SCHEDULER_READER` | `essentials_scheduler_reader` | View scheduled jobs |

**Access logic**: `ESSENTIALS_ADMIN` grants all permissions. Missing roles → redirect to `/access-denied`.

### Built-in Implementations

Located in `EssentialsAuthenticatedUser` interface as inner classes:

| Class | Use Case |
|-------|----------|
| `EssentialsAuthenticatedUser.AllAccessAuthenticatedUser` | Dev/testing - all roles granted |
| `EssentialsAuthenticatedUser.NoAccessAuthenticatedUser` | Testing - no access |

## UI Components

### AdminMainLayout

**Package**: `dk.trustworks.essentials.ui.view`
**Class**: `AdminMainLayout extends com.vaadin.flow.component.applayout.AppLayout`

**Structure**:
- Header: DrawerToggle + Logo + Title
- Drawer: SideNav (role-based) + UserMenu (avatar + logout)
- Content: Routed views

### ViewToolbar

**Package**: `dk.trustworks.essentials.ui.component`
**Class**: `ViewToolbar extends com.vaadin.flow.component.Composite<com.vaadin.flow.component.html.Header>`

**Components**: Drawer toggle, view title, action buttons (right-aligned)

**Usage**:
```java
new ViewToolbar("View Title", actionButton1, actionButton2);
```

### SecurityUtils

**Package**: `dk.trustworks.essentials.ui.util`
**Class**: `SecurityUtils`

**Methods**:
```java
boolean canAccessLocks();
boolean canWriteLocks();
boolean canAccessQueues();
boolean canWriteQueues();
boolean canAccessSubscriptions();
boolean canAccessPostgresqlStats();
boolean canAccessScheduler();
```

## Integration Patterns

### Pattern 1: Spring Boot Starter (Recommended)

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-admin-ui</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

Auto-configures:
- Vaadin for `dk.trustworks.essentials.ui` package
- Component scanning for admin views
- Requires `spring-boot-starter-postgresql-event-store`

### Pattern 2: Manual Configuration

```java
import com.vaadin.flow.spring.annotation.EnableVaadin;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@EnableVaadin("dk.trustworks.essentials.ui")
@ComponentScan(basePackages = {
    "your.app.package",
    "dk.trustworks.essentials.ui"
})
public class YourApplication { }
```

### Required Vaadin Dependency

```xml
<dependency>
    <groupId>com.vaadin</groupId>
    <artifactId>vaadin-spring-boot-starter</artifactId>
    <version>${vaadin.version}</version>
</dependency>
```

## Common Patterns

### Pattern: Navigation Guard

```java
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import dk.trustworks.essentials.ui.view.AccessDeniedView;

@Override
public void beforeEnter(BeforeEnterEvent event) {
    if (!securityUtils.canAccessQueues()) {
        event.forwardTo(AccessDeniedView.class);
    }
}
```

### Pattern: Conditional Action Columns

```java
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.notification.Notification;

if (securityUtils.canWriteQueues()) {
    grid.addComponentColumn(msg -> new Button("Delete", click -> {
        api.deleteMessage(authenticatedUser.getPrincipal(), msg.id());
        Notification.show("Deleted: " + msg.id());
        refreshGrids();
    })).setHeader("Actions");
}
```

### Pattern: Lazy Data Provider (Pagination)

```java
import com.vaadin.flow.data.provider.DataProvider;

grid.setDataProvider(DataProvider.fromCallbacks(
    query -> {
        int offset = query.getOffset();
        int limit = query.getLimit();
        return api.getMessages(queueName, offset, limit).stream();
    },
    query -> api.getTotalMessageCount(queueName)
));
```

### Pattern: Filterable Data Provider

```java
import com.vaadin.flow.data.provider.ConfigurableFilterDataProvider;
import com.vaadin.flow.data.provider.DataProvider;
import dk.trustworks.essentials.components.foundation.messaging.queue.QueueEntryId;

ConfigurableFilterDataProvider<ApiQueuedMessage, Void, String> provider =
    DataProvider.<ApiQueuedMessage, String>fromFilteringCallbacks(
        query -> {
            String filter = query.getFilter().orElse(null);
            if (filter != null) {
                return api.getMessageById(QueueEntryId.of(filter)).stream();
            }
            return api.getMessages(queueName, query.getOffset(), query.getLimit()).stream();
        },
        query -> {
            String filter = query.getFilter().orElse(null);
            return filter != null ? 1 : api.getTotalMessages(queueName);
        }
    ).withConfigurableFilter();

grid.setDataProvider(provider);
searchField.addValueChangeListener(e -> provider.setFilter(e.getValue()));
```

### Pattern: User Context in API Calls

```java
import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;

// All API calls include authenticated user principal for multi-tenancy
Set<QueueName> queues = durableQueuesApi.getQueueNames(
    authenticatedUser.getPrincipal()
);

List<ApiDBFencedLock> locks = dbfencedLockApi.getAllLocks(
    authenticatedUser.getPrincipal()
);
```

### Pattern: Spring Security Integration

```java
import com.vaadin.flow.spring.security.AuthenticationContext;
import dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser;
import dk.trustworks.essentials.shared.security.EssentialsSecurityRoles;
import org.springframework.stereotype.Component;

@Component
public class SpringSecurityAuthenticatedUser implements EssentialsAuthenticatedUser {
    private final AuthenticationContext authContext;

    public SpringSecurityAuthenticatedUser(AuthenticationContext authContext) {
        this.authContext = authContext;
    }

    @Override
    public Object getPrincipal() {
        return authContext;
    }

    @Override
    public boolean isAuthenticated() {
        return authContext.isAuthenticated();
    }

    @Override
    public boolean hasLockReaderRole() {
        return authContext.hasRole(EssentialsSecurityRoles.LOCK_READER.getRoleName());
    }

    @Override
    public void logout() {
        authContext.logout();
    }

    // ... implement remaining methods
}
```

## Gotchas

- ⚠️ **Performance**: Loading highest global order in Subscriptions view queries EventStore - avoid frequent calls
- ⚠️ **Memory**: Use lazy data providers (`DataProvider.fromCallbacks`) for large queues - don't load all at once
- ⚠️ **Security**: Check permissions in both UI (navigation guards) AND API layer
- ⚠️ **Component Scope**: Views must be `@UIScope` or `@RouteScope`, NOT singleton
- ⚠️ **User Context**: Always pass `authenticatedUser.getPrincipal()` to API calls
- ⚠️ **Grid Refresh**: Call `grid.getDataProvider().refreshAll()` after mutations
- ⚠️ **Payload Display**: Queue payload truncated in grid - full payload in tooltip (requires `QUEUE_PAYLOAD_READER`)

## API Dependencies by View

| View | Required Bean Interface(s) | Source Module |
|------|---------------------------|---------------|
| `LocksView` | `dk.trustworks.essentials.components.foundation.fencedlock.api.DBFencedLockApi` | foundation |
| `QueuesView` | `dk.trustworks.essentials.components.foundation.messaging.queue.api.DurableQueuesApi` | foundation |
| `SubscriptionsView` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api.EventStoreApi` | postgresql-event-store |
| `EventProcessorsView` | none | - |
| `PostgresqlStatisticsView` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api.PostgresqlEventStoreStatisticsApi`<br>`dk.trustworks.essentials.components.foundation.postgresql.api.PostgresqlQueryStatisticsApi` | postgresql-event-store<br>foundation |
| `SchedulerView` | `dk.trustworks.essentials.components.foundation.scheduler.api.SchedulerApi` | foundation |
| All views | `dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser` | shared |

## Test References

Key test files: See [README Testing Section](../components/vaadin-ui/README.md#testing)

## Related Modules

- [spring-boot-starter-admin-ui](../components/spring-boot-starter-admin-ui/README.md) - Auto-configuration
- [foundation](./LLM-foundation.md) - Core APIs (locks, queues, scheduler)
- [postgresql-event-store](./LLM-postgresql-event-store.md) - EventStore API
- [postgresql-distributed-fenced-lock](./LLM-postgresql-distributed-fenced-lock.md) - Lock implementation
- [postgresql-queue](./LLM-postgresql-queue.md) - Queue implementation
- [shared](./LLM-shared.md) - Security interfaces
