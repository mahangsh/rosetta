package com.adaptivescale.rosetta.ddl.targets.mysql;

import com.adaptivescale.rosetta.common.annotations.RosettaModule;
import com.adaptivescale.rosetta.common.models.Column;
import com.adaptivescale.rosetta.common.models.Database;
import com.adaptivescale.rosetta.common.models.ForeignKey;
import com.adaptivescale.rosetta.common.models.Table;
import com.adaptivescale.rosetta.common.types.RosettaModuleTypes;
import com.adaptivescale.rosetta.ddl.DDL;
import com.adaptivescale.rosetta.ddl.change.model.ColumnChange;
import com.adaptivescale.rosetta.ddl.change.model.ForeignKeyChange;
import com.adaptivescale.rosetta.ddl.targets.ColumnSQLDecoratorFactory;
import lombok.extern.slf4j.Slf4j;

import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RosettaModule(
        name = "mysql",
        type = RosettaModuleTypes.DDL_GENERATOR
)
public class MySqlDDLGenerator implements DDL {

    private final ColumnSQLDecoratorFactory columnSQLDecoratorFactory = new MySqlColumnDecoratorFactory();

    @Override
    public String createColumn(Column column) {
        return columnSQLDecoratorFactory.decoratorFor(column).expressSQl();
    }

    @Override
    public String createTable(Table table, boolean dropTableIfExists) {
        List<String> definitions = table.getColumns().stream().map(this::createColumn).collect(Collectors.toList());

        Optional<String> primaryKeysForTable = createPrimaryKeysForTable(table);
        primaryKeysForTable.ifPresent(definitions::add);
        String definitionAsString = String.join(", ", definitions);

        StringBuilder stringBuilder = new StringBuilder();
        if (dropTableIfExists) {
            stringBuilder.append("DROP TABLE IF EXISTS ");
            if (table.getSchema() != null && !table.getSchema().isBlank()) {
                stringBuilder.append("`").append(table.getSchema()).append("`.");
            }
            stringBuilder.append("`").append(table.getName()).append("`").append("; \n");
        }

        stringBuilder.append("CREATE TABLE ");

        if (table.getSchema() != null && !table.getSchema().isBlank()) {
            stringBuilder.append("`")
                    .append(table.getSchema())
                    .append("`.");
        }

        stringBuilder.append("`").append(table.getName()).append("`").append("(").append(definitionAsString).append(");");
        return stringBuilder.toString();
    }

    @Override
    public String createDatabase(Database database, boolean dropTableIfExists) {
        StringBuilder stringBuilder = new StringBuilder();

        Set<String> schemas = database.getTables().stream().map(Table::getSchema).filter(s -> s != null && !s.isEmpty()).collect(Collectors.toSet());
        if (!schemas.isEmpty()) {
            stringBuilder.append(
                    schemas
                            .stream()
                            .map(schema -> "CREATE SCHEMA IF NOT EXISTS `" + schema + "`")
                            .collect(Collectors.joining(";\r\r"))

            );
            stringBuilder.append(";\r");
        }

        stringBuilder.append(database.getTables()
                .stream()
                .map(table -> createTable(table, dropTableIfExists))
                .collect(Collectors.joining("\r\r")));

        String foreignKeys = database
                .getTables()
                .stream()
                .map(this::foreignKeys)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.joining());

        if (!foreignKeys.isEmpty()) {
            stringBuilder.append("\r").append(foreignKeys).append("\r");
        }
        return stringBuilder.toString();
    }

    @Override
    public String createForeignKey(ForeignKey foreignKey) {
        return "ALTER TABLE" + handleNullSchema(foreignKey.getSchema(), foreignKey.getTableName()) + " ADD CONSTRAINT "
                + foreignKey.getName() + " FOREIGN KEY (`" + foreignKey.getColumnName() + "`) REFERENCES "
                + handleNullSchema(foreignKey.getPrimaryTableSchema(), foreignKey.getPrimaryTableName())
                + "(`" + foreignKey.getPrimaryColumnName() + "`)"
                + foreignKeyDeleteRuleSanitation(foreignKeyDeleteRule(foreignKey)) + ";\r";
    }

    //use this to handle primary keys
    @Override
    public String alterTable(Table expected, Table actual) {

        boolean doesPKExist = actual.getColumns().stream().map(Column::isPrimaryKey).reduce((aBoolean, aBoolean2) -> aBoolean || aBoolean2).orElse(false);
        boolean doWeNeedToCreatePk = expected.getColumns().stream().map(Column::isPrimaryKey).reduce((aBoolean, aBoolean2) -> aBoolean || aBoolean2).orElse(false);

        StringBuilder stringBuilder = new StringBuilder("ALTER TABLE")
                .append(handleNullSchema(expected.getSchema(), expected.getName()));

        if (doesPKExist) {
            stringBuilder.append(" DROP PRIMARY KEY");
        }

        if (doWeNeedToCreatePk) {
            Optional<String> primaryKeysForTable = createPrimaryKeysForTable(expected);
            if (primaryKeysForTable.isPresent()) {
                if (doesPKExist) {
                    stringBuilder.append(",");
                }
                stringBuilder.append(" ADD ").append(primaryKeysForTable.get());
            }
        }

        stringBuilder.append(";");
        return stringBuilder.toString();
    }

    @Override
    public String alterColumn(ColumnChange change) {
        Table table = change.getTable();
        Column actual = change.getActual();
        Column expected = change.getExpected();

        if (!Objects.equals(expected.getTypeName(), actual.getTypeName())
                || !Objects.equals(expected.isNullable(), actual.isNullable())) {
            return String.format("ALTER TABLE%s MODIFY %s;",
                    handleNullSchema(table.getSchema(), table.getName()),
                    columnSQLDecoratorFactory.decoratorFor(expected).expressSQl());
        }

        log.info("No action taken for changes detected in column: {}.{}.{}", change.getTable().getSchema(),
                change.getTable().getName(),
                expected.getName());
        return "";
    }

    @Override
    public String dropColumn(ColumnChange change) {
        Table table = change.getTable();
        Column actual = change.getActual();

        return "ALTER TABLE" +
                handleNullSchema(table.getSchema(), table.getName()) + " DROP COLUMN `" +
                actual.getName() + "`;";
    }

    @Override
    public String addColumn(ColumnChange change) {
        Table table = change.getTable();
        Column expected = change.getExpected();

        return "ALTER TABLE" +
                handleNullSchema(table.getSchema(), table.getName()) +
                " ADD COLUMN " +
                columnSQLDecoratorFactory.decoratorFor(expected).expressSQl() + ";";
    }


    @Override
    public String dropTable(Table actual) {
        return "DROP TABLE" + handleNullSchema(actual.getSchema(), actual.getName()) + ";";
    }

    @Override
    public String alterForeignKey(ForeignKeyChange change) {
        return "";
    }

    @Override
    public String dropForeignKey(ForeignKey actual) {
        return "ALTER TABLE" + handleNullSchema(actual.getSchema(), actual.getTableName()) + " DROP FOREIGN KEY `" + actual.getName() + "`;";
    }


    private Optional<String> createPrimaryKeysForTable(Table table) {
        List<String> primaryKeys = table
                .getColumns()
                .stream()
                .filter(Column::isPrimaryKey)
                .sorted((o1, o2) -> o1.getPrimaryKeySequenceId() < o2.getPrimaryKeySequenceId() ? -1 : 1)
                .map(pk -> String.format("`%s`", pk.getName()))
                .collect(Collectors.toList());

        if (primaryKeys.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of("PRIMARY KEY (" + String.join(", ", primaryKeys) + ")");
    }

    private Optional<String> foreignKeys(Table table) {
        String result = table.getColumns().stream()
                .filter(column -> column.getForeignKeys() != null && !column.getForeignKeys().isEmpty())
                .map(this::createForeignKeys).collect(Collectors.joining());

        return result.isEmpty() ? Optional.empty() : Optional.of(result);
    }

    //ALTER TABLE rosetta.contacts ADD CONSTRAINT contacts_fk FOREIGN KEY (contact_id) REFERENCES rosetta."user"(user_id);
    private String createForeignKeys(Column column) {
        return column.getForeignKeys().stream().map(this::createForeignKey).collect(Collectors.joining());
    }

    private String handleNullSchema(String schema, String tableName) {
        return ((schema == null || schema.isEmpty()) ? " " : (" `" + schema + "`.")) + "`" + tableName + "`";
    }

    private String foreignKeyDeleteRuleSanitation(String deleteRule) {
        if (deleteRule == null || deleteRule.isEmpty()) {
            return "";
        }
        return " " + deleteRule + " ";
    }

    private String foreignKeyDeleteRule(ForeignKey foreignKey) {
        if (foreignKey.getDeleteRule() == null || foreignKey.getDeleteRule().isEmpty()) {
            return "";
        }
        switch (Integer.parseInt(foreignKey.getDeleteRule())) {
            case DatabaseMetaData.importedKeyCascade:
                return "ON DELETE CASCADE";
            case DatabaseMetaData.importedKeySetNull:
                return "ON DELETE SET NULL";
            case DatabaseMetaData.importedKeyNoAction:
                return "ON DELETE NO ACTION";
            case DatabaseMetaData.importedKeySetDefault:
            case DatabaseMetaData.importedKeyInitiallyDeferred:
            case DatabaseMetaData.importedKeyInitiallyImmediate:
            case DatabaseMetaData.importedKeyNotDeferrable:
            default:
                //todo add warn log
                return "";
        }
    }
}
