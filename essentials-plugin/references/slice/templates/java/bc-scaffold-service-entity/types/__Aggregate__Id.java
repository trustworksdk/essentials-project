package {{packagePath}}.{{bc}}.types;

import dk.trustworks.essentials.types.CharSequenceType;
import dk.trustworks.essentials.types.Identifier;
import dk.trustworks.essentials.components.foundation.types.RandomIdGenerator;

/**
 * Strongly-typed aggregate id for the {{Bc}} bounded context.
 *
 * BC-internal value object — lives in {@code {{bc}}/types/} because it is shared by several slices
 * within this BC. A type used by only ONE slice stays inside that slice's directory; do not
 * prematurely promote.
 *
 * Never use a raw {@code String} or {@code UUID} for an identity — a semantic type is what stops an
 * {{Aggregate}}Id being passed where some other id was meant.
 *
 * Jackson needs only the {@code CharSequence} constructor: {@code types-jackson3} pins a value type's
 * single-argument constructor as its delegating creator. The {@code String} one is a convenience.
 */
public class {{Aggregate}}Id extends CharSequenceType<{{Aggregate}}Id> implements Identifier {

    public {{Aggregate}}Id(CharSequence value) {
        super(value);
    }

    public {{Aggregate}}Id(String value) {
        super(value);
    }

    public static {{Aggregate}}Id of(CharSequence value) {
        return new {{Aggregate}}Id(value);
    }

    public static {{Aggregate}}Id random() {
        return new {{Aggregate}}Id(RandomIdGenerator.generate());
    }
}
