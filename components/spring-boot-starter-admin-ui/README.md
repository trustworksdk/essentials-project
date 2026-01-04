# Essentials Components - Spring Boot Starter: Admin UI

> **NOTE:** **The library is WORK-IN-PROGRESS**

Spring Boot auto-configuration for the Essentials Vaadin Admin UI with PostgreSQL EventStore integration.

**LLM Context:** [LLM-spring-boot-starter-modules.md](../../LLM/LLM-spring-boot-starter-modules.md)

## Table of Contents

- [Maven Dependency](#maven-dependency)
- [Overview](#overview)
- [What Gets Auto-Configured](#what-gets-auto-configured)
- [Required Setup](#required-setup)
  - [Authentication Provider](#authentication-provider)
  - [Spring Security Configuration](#spring-security-configuration)
- [Complete Example](#complete-example)
- [Accessing the Admin UI](#accessing-the-admin-ui)
- [Typical Dependencies](#typical-dependencies)
- [Related Modules](#related-modules)

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-admin-ui</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

## Overview

This starter combines the [vaadin-ui](../vaadin-ui/README.md) module with [spring-boot-starter-postgresql-event-store](../spring-boot-starter-postgresql-event-store/README.md) to provide a complete admin dashboard for PostgreSQL-based Essentials applications.

**What It Provides:**
- Pre-built admin views for locks, queues, subscriptions, statistics, and scheduler
- Automatic Vaadin configuration and component scanning
- Integration with PostgreSQL EventStore components
- Role-based access control for all admin features

**You Must Provide:**
- An `EssentialsAuthenticatedUser` implementation
- Spring Security configuration for login/authentication
- Vaadin Spring Boot Starter dependency

---

## What Gets Auto-Configured

The `EssentialsAdminUIAutoConfiguration` class:

```java
@AutoConfiguration
@EnableVaadin("dk.trustworks.essentials.ui")
@ComponentScan("dk.trustworks.essentials.ui")
public class EssentialsAdminUIAutoConfiguration {
}
```

This enables and registers all admin views from the `vaadin-ui` module:

| View | Route | Description |
|------|-------|-------------|
| `AdminView` | `/` | Welcome page |
| `LocksView` | `/locks` | Distributed lock management |
| `QueuesView` | `/queues` | Durable queue management |
| `SubscriptionsView` | `/subscriptions` | EventStore subscription monitoring |
| `EventProcessorsView` | `/eventprocessors` | Event processor status |
| `PostgresqlStatisticsView` | `/postgresql` | Database statistics |
| `SchedulerView` | `/scheduler` | Scheduled job monitoring |
| `AdminLoginView` | `/login` | Login form |
| `AccessDeniedView` | `/access-denied` | Access denied page |

---

## Required Setup

### Authentication Provider

You must provide an `EssentialsAuthenticatedUser` implementation as a Spring bean. This integrates with your authentication framework (typically Spring Security).

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

### Spring Security Configuration

Configure Spring Security for Vaadin:

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig extends VaadinWebSecurity {

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        super.configure(http);
        setLoginView(http, AdminLoginView.class);
    }

    @Bean
    public UserDetailsService users() {
      ...
    }
}
```

---

## Accessing the Admin UI

Once configured, the admin UI is available at:

| URL | Description |
|-----|-------------|
| `http://localhost:8080/` | Admin home page |
| `http://localhost:8080/login` | Login page |
| `http://localhost:8080/locks` | Locks management |
| `http://localhost:8080/queues` | Queues management |
| `http://localhost:8080/subscriptions` | Subscriptions monitoring |
| `http://localhost:8080/postgresql` | PostgreSQL statistics |
| `http://localhost:8080/scheduler` | Scheduler monitoring |

---

## Typical Dependencies

The starter brings in these Essentials modules transitively:

| Dependency | Description |
|------------|-------------|
| `spring-boot-starter-postgresql-event-store` | PostgreSQL EventStore with all foundation components |
| `vaadin-ui` | Pre-built admin UI views |

You must add these dependencies yourself (provided scope):

| Dependency | Purpose |
|------------|---------|
| `vaadin-spring-boot-starter` | Vaadin framework |
| `spring-boot-starter-security` | Authentication/authorization |
| `postgresql` | PostgreSQL JDBC driver |

Example:
```xml
<dependencies>
    <!-- Essentials Admin UI Starter -->
    <dependency>
        <groupId>dk.trustworks.essentials.components</groupId>
        <artifactId>spring-boot-starter-admin-ui</artifactId>
        <version>${essentials.version}</version>
    </dependency>

    <!-- Vaadin (required - provided scope in starter) -->
    <dependency>
        <groupId>com.vaadin</groupId>
        <artifactId>vaadin-spring-boot-starter</artifactId>
        <version>${vaadin.version}</version>
    </dependency>

    <!-- Spring Security -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>

    <!-- PostgreSQL -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
    </dependency>
</dependencies>
```

---

## Related Modules

| Module | Description |
|--------|-------------|
| [vaadin-ui](../vaadin-ui/README.md) | Admin UI views and components |
| [spring-boot-starter-postgresql-event-store](../spring-boot-starter-postgresql-event-store/README.md) | EventStore auto-configuration |
| [spring-boot-starter-postgresql](../spring-boot-starter-postgresql/README.md) | Foundation components auto-configuration |
| [foundation](../foundation/README.md) | Core interfaces (FencedLock, DurableQueues) |
| [shared](../../shared/README.md) | Security interfaces (`EssentialsAuthenticatedUser`) |
