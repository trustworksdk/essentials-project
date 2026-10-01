package com.example.shop.orders.automations.fulfillment;

import dk.trustworks.essentials.components.document_db.JavaVersionedEntity;
import dk.trustworks.essentials.components.document_db.Version;
import dk.trustworks.essentials.components.document_db.annotations.DocumentEntity;
import dk.trustworks.essentials.components.document_db.annotations.Id;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Explicit process state for the fulfillment automation — what has happened and what may happen next.
 *
 * Make the guards explicit ({@code canProceed()}) rather than scattering boolean conditions across
 * handlers: the guard is the process's rule, and it is the thing worth testing.
 *
 * A stateless automation (one event in, one command out) does not need this file — delete it and
 * the repository if the process has no memory.
 *
 * Initialise {@code version} to {@code Version.NOT_SAVED_YET_VALUE} (-1), not 0.
 */
@DocumentEntity(tableName = "orders_fulfillment_todo")
public class FulfillmentTodoList extends JavaVersionedEntity<String, FulfillmentTodoList> {

    @Id
    public String id;   // MUST be public — DocumentDB reads @Id by field access; a private one fails the first save

    private boolean started;
    private boolean dispatched;
    private boolean completed;
    private int attempts;

    private long version = Version.NOT_SAVED_YET_VALUE;
    private OffsetDateTime lastUpdated = OffsetDateTime.now(ZoneOffset.UTC);

    public FulfillmentTodoList() {
    }

    public FulfillmentTodoList(String id) {
        this.id = id;
    }

    /** TODO: the real precondition for the next step. Bound the retries. */
    public boolean canProceed() {
        return started && !dispatched && attempts < 3;
    }

    @Override public long getVersionValue()                            { return version; }
    @Override public void setVersionValue(long version)                { this.version = version; }
    @Override public OffsetDateTime getLastUpdated()                   { return lastUpdated; }
    @Override public void setLastUpdated(OffsetDateTime lastUpdated)   { this.lastUpdated = lastUpdated; }

    public String getId()                       { return id; }
    public boolean isStarted()                  { return started; }
    public void setStarted(boolean started)     { this.started = started; }
    public boolean isDispatched()               { return dispatched; }
    public void setDispatched(boolean d)        { this.dispatched = d; }
    public boolean isCompleted()                { return completed; }
    public void setCompleted(boolean c)         { this.completed = c; }
    public int getAttempts()                    { return attempts; }
    public void setAttempts(int attempts)       { this.attempts = attempts; }
}
