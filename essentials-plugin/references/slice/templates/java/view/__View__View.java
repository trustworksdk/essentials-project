package {{packagePath}}.{{bc}}.views.{{view}};

import dk.trustworks.essentials.components.document_db.JavaVersionedEntity;
import dk.trustworks.essentials.components.document_db.Version;
import dk.trustworks.essentials.components.document_db.annotations.DocumentEntity;
import dk.trustworks.essentials.components.document_db.annotations.Id;
import dk.trustworks.essentials.components.document_db.annotations.Indexed;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Read model for the {{view}} view slice. Owned by THIS slice — no other slice reads or writes it.
 *
 * Java entities extend {@link JavaVersionedEntity} rather than implementing {@code VersionedEntity}
 * directly: the bridge implements the Kotlin {@code Version} value class in terms of two primitive
 * {@code long} accessors, so Java never touches it.
 *
 * {@code version} carries the projected event's {@code EventOrder}, which is what makes the
 * projection idempotent under redelivery.
 *
 * THREE THINGS THAT BITE:
 *  - the {@code @Id} field MUST be {@code public} — see the note on the field below. A private
 *    {@code @Id} compiles, starts, and then never populates the projection;
 *  - initialise {@code version} to {@code Version.NOT_SAVED_YET_VALUE} (-1), NOT 0 — 0 means
 *    "saved at version zero" and fails the insert path;
 *  - the field names {@code version} and {@code lastUpdated} are hardcoded in the reflection layer.
 *    Do not rename them, and keep them mutable.
 *
 * Alternative persistence: a JDBI {@code @SqlObject} repository over a Flyway-managed table is also
 * supported by Essentials, and is the right choice if you need hand-tuned SQL or the view must live
 * in an existing relational schema. This template uses DocumentDB, which manages its own table and
 * indexes; swapping to JDBI means adding a migration and writing the SQL yourself.
 */
@DocumentEntity(tableName = "{{bc}}_{{view}}")
public class {{View}}View extends JavaVersionedEntity<String, {{View}}View> {

    /**
     * MUST be {@code public}. {@code EntityConfiguration} resolves {@code @Id} through Kotlin
     * reflection over {@code memberProperties}. For a Java class the property is synthesised from
     * the <em>field</em>, so reading it is a direct field access ({@code CallerImpl$FieldGetter}) —
     * not a call to {@code get{{Aggregate}}Id()}. A private field therefore throws
     * {@code IllegalAccessException} wrapped in {@code ReflectionException}, the message is
     * dead-lettered, and the projection silently never populates. Nothing fails at startup, so this
     * presents as "the projection just doesn't work".
     *
     * <p>{@code version} and {@code lastUpdated} escape this because they are declared on
     * {@link JavaVersionedEntity} as Kotlin properties with real getter methods — their
     * {@code KProperty1} getter is a method call, not a field read. The asymmetry is not arbitrary:
     * Kotlin-declared property → method call; Java-declared field → field read.
     */
    @Id
    public String {{aggregate}}Id;

    // TODO: replace with the fields this view serves
    @Indexed
    private String status;

    private long version = Version.NOT_SAVED_YET_VALUE;
    private OffsetDateTime lastUpdated = OffsetDateTime.now(ZoneOffset.UTC);

    public {{View}}View() {
    }

    public {{View}}View(String {{aggregate}}Id, String status) {
        this.{{aggregate}}Id = {{aggregate}}Id;
        this.status = status;
    }

    @Override
    public long getVersionValue() {
        return version;
    }

    @Override
    public void setVersionValue(long version) {
        this.version = version;
    }

    @Override
    public OffsetDateTime getLastUpdated() {
        return lastUpdated;
    }

    @Override
    public void setLastUpdated(OffsetDateTime lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public String get{{Aggregate}}Id() {
        return {{aggregate}}Id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
