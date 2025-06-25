package dk.trustworks.essentials.components.foundation.ttl;

public enum ComparisonOperator {
    LESS_THAN("<"),
    LESS_THAN_EQUALS("<="),
    LESS_THAN_EQUALS_NOW("<= NOW() - INTERVAL '%d day'");

    private final String sql;
    ComparisonOperator(String sql) {
        this.sql = sql;
    }
    public String getSql() {
        return sql;
    }
}
