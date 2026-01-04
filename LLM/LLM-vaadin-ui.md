# Vaadin UI - LLM Reference

> See [README](../components/vaadin-ui/README.md) for detailed developer documentation.

## Quick Facts
- **Package**: `dk.trustworks.essentials.ui`
- **Purpose**: Pre-built admin web UI for monitoring/managing Essentials components
- **Key deps**: Vaadin Flow (provided), Spring Boot (provided)
- **Status**: WORK-IN-PROGRESS

## TOC
- [Admin Views](#admin-views)
- [Security/Roles](#securityroles)
- [UI Components](#ui-components)
- [Integration](#integration)
- [Common Patterns](#common-patterns)

## Admin Views

| Route | Class | Required Role | Purpose |
|-------|-------|---------------|---------|
| `/locks` | `LocksView` | `LOCK_READER` | View/release distributed locks |
| `/queues` | `QueuesView` | `QUEUE_READER` | Browse/manage queue messages |
| `/subscriptions` | `SubscriptionsView` | `SUBSCRIPTION_READER` | Monitor EventStore subscriptions |
| `/eventprocessors` | `EventProcessorsView` | `SUBSCRIPTION_READER` | Control event processors |
| `/postgresql` | `PostgresqlStatisticsView` | `POSTGRESQL_STATS_READER` | DB performance stats |
| `/scheduler` | `SchedulerView` | `SCHEDULER_READER` | View scheduled jobs |
| `/login` | `AdminLoginView` | Public | Authentication |
| `/access-denied` | `AccessDeniedView` | Public | 403 page |

### Locks View

**Features:**
- View all active locks (name, token, instance ID, timestamps)
- Release locks (requires `LOCK_WRITER`)

**Required Bean:** `DBFencedLockApi`

**Grid Columns:**
- Lock Name
- Current Token
- Locked By (instance ID)
- Lock Acquired (timestamp)
- Last Confirmed (timestamp)
- Actions (Release button)

### Queues View

**Features:**
- Queue selector dropdown
- Queue statistics (total delivered, avg latency, first/last delivery)
- Browse queued messages (paginated)
- Browse dead-letter messages
- Search by queue entry ID
- Actions: Mark as dead-letter, Delete, Resurrect (requires `QUEUE_WRITER`)

**Required Bean:** `DurableQueuesApi`

**Message Grid Columns:**
- ID
- Payload (truncated, tooltip shows full)
- Added Timestamp
- Next Delivery Timestamp
- Total Delivery Attempts
- Redelivery Attempts
- Being Delivered (boolean)
- Actions (Deadletter/Delete/Resurrect)

### Subscriptions View

**Features:**
- View all EventStore subscriptions
- Show subscriber ID, aggregate type, global order, last updated
- Load highest global event order per aggregate type (on-demand, performance impact)

**Required Bean:** `EventStoreApi`

### PostgreSQL Statistics View

**Metrics:**

| Section | Data |
|---------|------|
| Table Size | Total size, table size, index size per table |
| Table Activity | Sequential scans, tuples read, index scans, inserts, updates, deletes |
| Cache Hit Ratio | Hit percentage per table |
| Slow Queries | Top 10 by total time, mean time, call count |

**Required Beans:**
- `PostgresqlEventStoreStatisticsApi`
- `PostgresqlQueryStatisticsApi`

### Scheduler View

**Features:**
- **PgCron Jobs**: ID, schedule (cron), command, active status
- **Scheduled Executor Jobs**: Name, initial delay, period, time unit, scheduled-at
- **PgCron Job Runs**: Job ID, run ID, command, status, return message, start/end timestamps

**Required Bean:** `SchedulerApi`

## Security/Roles

### EssentialsAuthenticatedUser Interface

**Must implement:**
```java
interface EssentialsAuthenticatedUser {
    Object getPrincipal();
    boolean isAuthenticated();
    boolean hasRole(String role);
    Optional<String> getPrincipalName();

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

    void logout();
}
```

### Role Mapping

| Enum | Role Name | Access |
|------|-----------|--------|
| `ESSENTIALS_ADMIN` | `essentials_admin` | Full access to all features |
| `LOCK_READER` | `essentials_lock_reader` | View locks |
| `LOCK_WRITER` | `essentials_lock_writer` | View + release locks |
| `QUEUE_READER` | `essentials_queue_reader` | View queues + metadata |
| `QUEUE_PAYLOAD_READER` | `essentials_queue_payload_reader` | View message payloads |
| `QUEUE_WRITER` | `essentials_queue_writer` | Manage messages |
| `SUBSCRIPTION_READER` | `essentials_subscription_reader` | View subscriptions |
| `SUBSCRIPTION_WRITER` | `essentials_subscription_writer` | Manage subscriptions |
| `POSTGRESQL_STATS_READER` | `essentials_postgresql_stats_reader` | View DB stats |
| `SCHEDULER_READER` | `essentials_scheduler_reader` | View scheduled jobs |

**Access Logic:**
- `ESSENTIALS_ADMIN` role grants all permissions
- Individual roles provide fine-grained access
- Missing roles → redirect to `/access-denied`

### Built-in Implementations

| Class | Use Case |
|-------|----------|
| `AllAccessAuthenticatedUser` | Dev/testing - all roles granted |
| `NoAccessAuthenticatedUser` | Testing - no access |

## UI Components

### AdminMainLayout

**Class:** `AdminMainLayout extends AppLayout`

**Structure:**
```
AppLayout
├── Header (HorizontalLayout)
│   ├── DrawerToggle
│   ├── Logo
│   └── Title
├── Drawer (VerticalLayout)
│   ├── SideNav (role-based menu items)
│   └── UserMenu (avatar + logout)
└── Content Area (routed views)
```

**Navigation:** Role-based visibility for menu items

### ViewToolbar

**Class:** `ViewToolbar extends HorizontalLayout`

**Components:**
- Drawer toggle button
- View title
- Action buttons area (right-aligned)

## Integration

### Option 1: Spring Boot Starter (Recommended)

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-admin-ui</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**Auto-configures:**
- Vaadin for `dk.trustworks.essentials.ui` package
- Component scanning for admin views
- Requires `spring-boot-starter-postgresql-event-store`

### Option 2: Manual

```java
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
@Override
public void beforeEnter(BeforeEnterEvent event) {
    if (!securityUtils.canAccessQueues()) {
        event.forwardTo(AccessDeniedView.class);
    }
}
```

### Pattern: Conditional Action Columns

```java
// Only show delete button if user has write permission
if (securityUtils.canWriteQueues()) {
    grid.addComponentColumn(msg -> new Button("Delete", click -> {
        api.deleteMessage(authenticatedUser.getPrincipal(), msg.id());
        Notification.show("Deleted: " + msg.id());
        refreshGrids();
    })).setHeader("Actions");
}
```

### Pattern: Lazy Data Provider

```java
// For large datasets - paginated loading
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
@Component
public class SpringSecurityAuthenticatedUser implements EssentialsAuthenticatedUser {
    private final AuthenticationContext authContext;

    public SpringSecurityAuthenticatedUser(AuthenticationContext authContext) {
        this.authContext = authContext;
    }

    @Override
    public boolean hasLockReaderRole() {
        return authContext.hasRole(
            EssentialsSecurityRoles.LOCK_READER.getRoleName()
        );
    }

    @Override
    public void logout() {
        authContext.logout();
    }
}
```

## Gotchas

- ⚠️ **Performance**: Loading highest global order in Subscriptions view queries EventStore - avoid frequent calls
- ⚠️ **Memory**: Use lazy data providers for large queues - don't load all messages at once
- ⚠️ **Security**: Check permissions in both UI (navigation guards) and API layer
- ⚠️ **Component Scope**: Views must be `@UIScope` or `@RouteScope`, not singleton
- ⚠️ **User Context**: Always pass `authenticatedUser.getPrincipal()` to API calls
- ⚠️ **Grid Refresh**: Call `grid.getDataProvider().refreshAll()` after mutations
- ⚠️ **Truncation**: Queue payload displayed truncated - full payload in tooltip (requires `QUEUE_PAYLOAD_READER`)

## Required Beans by View

| View | Required Bean(s)                                                    |
|------|---------------------------------------------------------------------|
| `LocksView` | `DBFencedLockApi`                                                   |
| `QueuesView` | `DurableQueuesApi`                                                  |
| `SubscriptionsView` | `EventStoreApi`                                                     |
| `EventProcessorsView` | none                                                                |
| `PostgresqlStatisticsView` | `PostgresqlEventStoreStatisticsApi`, `PostgresqlQueryStatisticsApi` |
| `SchedulerView` | `SchedulerApi`                                                      |
| All views | `EssentialsAuthenticatedUser`                                       |

## Related Modules

- [spring-boot-starter-admin-ui](../components/spring-boot-starter-admin-ui/README.md) - Auto-configuration
- [foundation](./LLM-foundation.md) - Core APIs (locks, queues, scheduler)
- [postgresql-event-store](./LLM-postgresql-event-store.md) - EventStore API
- [postgresql-distributed-fenced-lock](./LLM-postgresql-distributed-fenced-lock.md) - Lock implementation
- [postgresql-queue](./LLM-postgresql-queue.md) - Queue implementation
- [shared](./LLM-shared.md) - Security interfaces
