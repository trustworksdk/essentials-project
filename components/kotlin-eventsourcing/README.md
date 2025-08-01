# Essentials Components - Kotlin EventSourcing

This library provides an **experimental** Kotlin focused `Decider`/`Evolver` approach to EventSourcing, compatible with the `CommandBus` and integrating with the [postgresql-event-store](components/postgresql-event-store/README.md) library.

> **NOTE:**  
> **The library is WORK-IN-PROGRESS**


# Security

In general follow the security advises for the [`EventStore`](../postgresql-event-store/README.md).

Several of the Essentials components, as well as their subcomponents and/or supporting classes, allows the user of the components to provide customized:
- table names
- column names
- etc.

By using naming conventions for Postgresql table/column/index names, Essentials attempts to provide an initial layer of defense intended to reduce the risk of malicious input.    
**However, Essentials does not offer exhaustive protection, nor does it assure the complete security of the resulting SQL and Mongo Queries/Updates against injection threats.**
> The responsibility for implementing protective measures against malicious API input and configuration values lies  exclusively with the users/developers using the Essentials components and its supporting classes.
> Users must ensure thorough sanitization and validation of API input parameters, values, column names, function names, table names, and index names

**Insufficient attention to these practices may leave the application vulnerable to attacks, endangering the security and integrity of the database.**

It's highly recommended that customized values are only derived from a controlled and trusted source.  
**Failure on the users behalf to adequately sanitize and validate these values could expose the application to database specific vulnerabilities, such as SQL Injection or
modify unauthorized collections, all of which can compromise the security and integrity of the databases.**

# Aggregate-Id

In event sourcing, an Aggregate-Id is a unique identifier that groups together related events belonging to the same business entity (aggregate). It plays a crucial role in:

1. **Event Organization**: All events related to a specific aggregate instance share the same Aggregate-Id, allowing for easy tracking and retrieval of an aggregate's complete history.

2. **Stream Identification**: The Aggregate-Id helps identify which event stream an event belongs to, making it possible to rebuild the aggregate's state by replaying all events with the same ID.

3. **Concurrency Control**: Used to ensure that events for the same aggregate instance are processed in the correct order and to detect potential conflicts.

IMPORTANT: For security reasons, Aggregate-Id's should:

- Be generated using secure methods (e.g., `RandomIdGenerator#generate()` or `UUID#randomUUID()`)
- Never contain user-supplied input without proper validation
- Use safe characters to prevent SQL injection attacks when used in database operations that perform SQL string concatenation

> Please see the Java documentation and Readme's for the typical components that the Kotlin EventSourcing library is used with for more information.
> Such as:
> - [foundation-types](foundation-types/README.md)
> - [postgresql-distributed-fenced-lock](postgresql-distributed-fenced-lock/README.md)
> - [postgresql-queue](postgresql-queue/README.md)
> - [postgresql-event-store](postgresql-event-store/README.md)
