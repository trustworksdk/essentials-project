# Essentials Components - Vaadin UI

> **NOTE:** **The library is WORK-IN-PROGRESS**

This module provides a pre-built Vaadin admin UI for monitoring and managing Essentials components: distributed locks, queues, EventStore subscriptions, PostgreSQL statistics, and scheduled jobs.

**LLM Context:** [LLM-vaadin-ui.md](../../LLM/LLM-vaadin-ui.md)

## Table of Contents

- [Maven Dependency](#maven-dependency)
- [Overview](#overview)
- [Spring Boot Integration](#spring-boot-integration)
- [Authentication Setup](#authentication-setup)
- [Security Roles](#security-roles)
- [Admin Views](#admin-views)
  - [Locks View](#locks-view)
  - [Queues View](#queues-view)
  - [Subscriptions View](#subscriptions-view)
  - [PostgreSQL Statistics View](#postgresql-statistics-view)
  - [Scheduler View](#scheduler-view)
- [UI Components](#ui-components)
- [Related Modules](#related-modules)

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>vaadin-ui</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

Required dependencies (provided scope - add them yourself):

```xml
<dependency>
    <groupId>com.vaadin</groupId>
    <artifactId>vaadin-spring-boot-starter</artifactId>
    <version>${vaadin.version}</version>
</dependency>
```

## Overview

The Vaadin UI module provides an administrative web interface for Essentials components without requiring custom UI development.

**Key Features:**
- **Locks Management**: View active distributed locks, release stale locks
- **Queues Management**: Browse queued/dead-letter messages, resurrect or delete messages
- **Subscriptions Monitoring**: View EventStore subscriptions and resume points
- **PostgreSQL Statistics**: Table sizes, activity stats, cache hit ratios, slow queries
- **Scheduler Monitoring**: View PgCron jobs and scheduled executor jobs
- **Role-Based Access**: Fine-grained security per feature area

**Architecture:**

```
┌──────────────────────────────────────────────────────────────┐
│                    AdminMainLayout                           │
│  ┌──────────┐  ┌──────────────────────────────────────────┐  │
│  │ SideNav  │  │              Content Area                │  │
│  │          │  │  ┌─────────────────────────────────┐     │  │
│  │ • Locks  │  │  │  LocksView / QueuesView /       │     │  │
│  │ • Queues │  │  │  SubscriptionsView / ...        │     │  │
│  │ • Subs   │  │  └─────────────────────────────────┘     │  │
│  │ • Stats  │  │                                          │  │
│  │ • Jobs   │  │                                          │  │
│  └──────────┘  └──────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

---

## Spring Boot Integration

### Option 1: Use the Spring Boot Starter (Recommended)

The `spring-boot-starter-admin-ui` module auto-configures the admin UI:

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-admin-ui</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

This starter:
- Enables Vaadin for the `dk.trustworks.essentials.ui` package
- Component-scans all admin views automatically
- Requires `spring-boot-starter-postgresql-event-store` dependency

### Option 2: Manual Configuration

Enable Vaadin and component scanning:

```java
@SpringBootApplication
@EnableVaadin("dk.trustworks.essentials.ui")
@ComponentScan(basePackages = {
    "your.app.package",
    "dk.trustworks.essentials.ui"
})
public class YourApplication {
    public static void main(String[] args) {
        SpringApplication.run(YourApplication.class, args);
    }
}
```

---

## Authentication Setup

### Why

The admin UI requires an `EssentialsAuthenticatedUser` implementation to control access to features and track who performs administrative actions.

### Interface

User of this library must provide an implementation of the `EssentialsAuthenticatedUser` from the `shared` module:

```java
public interface EssentialsAuthenticatedUser {
    Object getPrincipal();
    boolean isAuthenticated();
    boolean hasRole(String role);
    Optional<String> getPrincipalName();

    // Role-specific methods
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

### Example: Spring Security Integration

```java
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
    public boolean hasRole(String role) {
        return authContext.hasRole(role);
    }

    @Override
    public Optional<String> getPrincipalName() {
        return authContext.getPrincipalName();
    }

    @Override
    public boolean hasAdminRole() {
        return authContext.hasRole(EssentialsSecurityRoles.ESSENTIALS_ADMIN.getRoleName());
    }

    @Override
    public boolean hasLockReaderRole() {
        return authContext.hasRole(EssentialsSecurityRoles.LOCK_READER.getRoleName());
    }

    @Override
    public boolean hasLockWriterRole() {
        return authContext.hasRole(EssentialsSecurityRoles.LOCK_WRITER.getRoleName());
    }

    @Override
    public boolean hasQueueReaderRole() {
        return authContext.hasRole(EssentialsSecurityRoles.QUEUE_READER.getRoleName());
    }

    @Override
    public boolean hasQueuePayloadReaderRole() {
        return authContext.hasRole(EssentialsSecurityRoles.QUEUE_PAYLOAD_READER.getRoleName());
    }

    @Override
    public boolean hasQueueWriterRole() {
        return authContext.hasRole(EssentialsSecurityRoles.QUEUE_WRITER.getRoleName());
    }

    @Override
    public boolean hasSubscriptionReaderRole() {
        return authContext.hasRole(EssentialsSecurityRoles.SUBSCRIPTION_READER.getRoleName());
    }

    @Override
    public boolean hasPostgresqlStatsReaderRole() {
        return authContext.hasRole(EssentialsSecurityRoles.POSTGRESQL_STATS_READER.getRoleName());
    }

    @Override
    public boolean hasSchedulerReaderRole() {
        return authContext.hasRole(EssentialsSecurityRoles.SCHEDULER_READER.getRoleName());
    }

    @Override
    public void logout() {
        authContext.logout();
    }
}
```

### Built-in Implementations

For development/testing, two implementations are provided:

| Class | Description |
|-------|-------------|
| `AllAccessAuthenticatedUser` | Always authenticated with all roles (development only) |
| `NoAccessAuthenticatedUser` | Never authenticated, no roles (testing) |

---

## Security Roles

The UI uses role-based access control via `EssentialsSecurityRoles`:

| Role | Role Name | Description |
|------|-----------|-------------|
| `ESSENTIALS_ADMIN` | `essentials_admin` | Full access to all features |
| `LOCK_READER` | `essentials_lock_reader` | View locks |
| `LOCK_WRITER` | `essentials_lock_writer` | View and release locks |
| `QUEUE_READER` | `essentials_queue_reader` | View queues and message metadata |
| `QUEUE_PAYLOAD_READER` | `essentials_queue_payload_reader` | View message payloads |
| `QUEUE_WRITER` | `essentials_queue_writer` | Manage messages (deadletter, resurrect, delete) |
| `SUBSCRIPTION_READER` | `essentials_subscription_reader` | View subscriptions and EventProcessors |
| `SUBSCRIPTION_WRITER` | `essentials_subscription_writer` | Manage subscriptions |
| `POSTGRESQL_STATS_READER` | `essentials_postgresql_stats_reader` | View PostgreSQL statistics |
| `SCHEDULER_READER` | `essentials_scheduler_reader` | View scheduled jobs |

**Access Control:**
- The `ESSENTIALS_ADMIN` role grants access to all features
- Individual roles provide fine-grained access control
- Users without required roles are redirected to the Access Denied page

---

## Admin Views

### Locks View

**Route:** `/locks`
**Class:** `LocksView`
**Required Role:** `LOCK_READER` or `ESSENTIALS_ADMIN`

**Purpose:** Monitor and manage distributed fenced locks.

**Features:**
- View all active locks with details:
  - Lock name
  - Current token (fence value)
  - Lock manager instance ID
  - Lock acquired timestamp
  - Last confirmed timestamp
- Release locks (requires `LOCK_WRITER` role)
- Refresh lock data

**Required Bean:** `DBFencedLockApi` (provided by [postgresql-distributed-fenced-lock](../postgresql-distributed-fenced-lock/README.md) or [springdata-mongo-distributed-fenced-lock](../springdata-mongo-distributed-fenced-lock/README.md))

---

### Queues View

**Route:** `/queues`
**Class:** `QueuesView`
**Required Role:** `QUEUE_READER` or `ESSENTIALS_ADMIN`

**Purpose:** Monitor and manage durable queues and their messages.

**Features:**
- Select a queue from the dropdown
- View queue statistics:
  - From timestamp
  - Total messages delivered
  - Average delivery latency (ms)
  - First/last delivery timestamps
- Browse queued messages:
  - ID, payload (truncated), timestamps
  - Delivery errors, attempt counts
  - Mark as dead-letter (requires `QUEUE_WRITER`)
  - Delete message (requires `QUEUE_WRITER`)
- Browse dead-letter messages:
  - Same fields as queued messages
  - Resurrect message (requires `QUEUE_WRITER`)
  - Delete message (requires `QUEUE_WRITER`)
- Search by queue entry ID
- Refresh data

**Required Bean:** `DurableQueuesApi` (provided by [postgresql-queue](../postgresql-queue/README.md) or [springdata-mongo-queue](../springdata-mongo-queue/README.md))

---

### Subscriptions View

**Route:** `/subscriptions`
**Class:** `SubscriptionsView`
**Required Role:** `SUBSCRIPTION_READER` or `ESSENTIALS_ADMIN`

**Purpose:** Monitor EventStore subscriptions and their progress.

**Features:**
- View all subscriptions:
  - Subscriber ID
  - Aggregate type
  - Current global order (resume point)
  - Last updated timestamp
- Load highest global event order per aggregate type (on-demand)
- Refresh subscription data

**Performance Note:** Loading the highest global order queries the EventStore and may impact performance if called frequently.

**Required Bean:** `EventStoreApi` (provided by [postgresql-event-store](../postgresql-event-store/README.md))

---

### PostgreSQL Statistics View

**Route:** `/postgresql`
**Class:** `PostgresqlStatisticsView`
**Required Role:** `POSTGRESQL_STATS_READER` or `ESSENTIALS_ADMIN`

**Purpose:** Monitor PostgreSQL database health and performance.

**Statistics Available:**

| Section | Metrics |
|---------|---------|
| **Table Size** | Total size, table size, index size per table |
| **Table Activity** | Sequential scans, tuples read, index scans, tuples fetched, inserts, updates, deletes |
| **Cache Hit Ratio** | Cache hit percentage per table |
| **Slow Queries** | Top 10 slowest queries by total time, mean time, call count |

**Required Beans:**
- `PostgresqlEventStoreStatisticsApi` (from `postgresql-event-store`)
- `PostgresqlQueryStatisticsApi` (from `foundation`)

---

### Scheduler View

**Route:** `/scheduler`
**Class:** `SchedulerView`
**Required Role:** `SCHEDULER_READER` or `ESSENTIALS_ADMIN`

**Purpose:** Monitor scheduled jobs.

**Features:**
- **PgCron Jobs:**
  - Job ID, schedule (cron expression), command, active status
- **Scheduled Executor Jobs:**
  - Name, initial delay, period, time unit, scheduled-at timestamp
- **PgCron Job Run Details:**
  - Job ID, run ID, command, status, return message
  - Start/end timestamps
  - Filter by job ID

**Required Bean:** `SchedulerApi` (from `foundation`)

---

## UI Components

### AdminMainLayout

**Class:** `AdminMainLayout`

The main layout component providing:
- Header with drawer toggle, app logo, title
- Side navigation with links to feature views
- User menu with logout

Navigation items are conditionally shown based on user roles.

### AdminLoginView

**Route:** `/login`

Standard Vaadin login form for authentication.

### AccessDeniedView

**Route:** `/access-denied`

Displayed when a user lacks permissions for a requested view. Returns HTTP 403 status.

### ViewToolbar

**Class:** `ViewToolbar`

Reusable toolbar component with:
- Drawer toggle button
- View title
- Action buttons area

---

## Related Modules

| Module | Description |
|--------|-------------|
| [spring-boot-starter-admin-ui](../spring-boot-starter-admin-ui) | Spring Boot auto-configuration for Vaadin UI |
| [foundation](../foundation/README.md) | Core interfaces (FencedLock, DurableQueues, Scheduler) |
| [postgresql-distributed-fenced-lock](../postgresql-distributed-fenced-lock/README.md) | PostgreSQL fenced lock implementation |
| [springdata-mongo-distributed-fenced-lock](../springdata-mongo-distributed-fenced-lock/README.md) | MongoDB fenced lock implementation |
| [postgresql-queue](../postgresql-queue/README.md) | PostgreSQL durable queue implementation |
| [springdata-mongo-queue](../springdata-mongo-queue/README.md) | MongoDB durable queue implementation |
| [postgresql-event-store](../postgresql-event-store/README.md) | PostgreSQL EventStore implementation |
| [shared](../../shared/README.md) | Security interfaces (`EssentialsAuthenticatedUser`, `EssentialsSecurityRoles`) |
