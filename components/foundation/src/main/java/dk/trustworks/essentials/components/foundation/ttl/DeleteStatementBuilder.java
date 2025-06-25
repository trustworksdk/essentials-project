package dk.trustworks.essentials.components.foundation.ttl;

import java.util.Locale;

public class DeleteStatementBuilder {

    private DeleteStatementBuilder() {  }

    public static String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    public static String build(String tableName,
                               String timestampColumn,
                               ComparisonOperator operator,
                               long days) {
        String where = buildWhereClause(timestampColumn, operator, days);
        return String.format(
                "DELETE FROM %s WHERE %s",
                quoteIdent(tableName),
                where
                            );
    }

    public static String buildWhereClause(String timestampColumn,
                                          ComparisonOperator operator,
                                          long days) {
        String col = quoteIdent(timestampColumn);
        String opSql;
        if (operator == ComparisonOperator.LESS_THAN
                || operator == ComparisonOperator.LESS_THAN_EQUALS) {
            opSql = operator.getSql();
        } else {
            opSql = String.format(Locale.ROOT, operator.getSql(), days);
        }
        return String.format(
                "%s %s (NOW() - INTERVAL '%d day')",
                col,
                opSql,
                days
                            );
    }
}
