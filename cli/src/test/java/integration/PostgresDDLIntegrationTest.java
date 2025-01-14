package integration;

import com.adaptivescale.rosetta.common.DriverManagerDriverProvider;
import com.adaptivescale.rosetta.common.models.AssertTest;
import com.adaptivescale.rosetta.common.models.Database;
import com.adaptivescale.rosetta.common.models.test.Tests;
import com.adaptivescale.rosetta.ddl.DDLFactory;
import com.adaptivescale.rosetta.ddl.change.PostgresChangeFinder;
import com.adaptivescale.rosetta.ddl.change.model.Change;
import com.adaptivescale.rosetta.ddl.executor.DDLExecutor;
import com.adaptivescale.rosetta.test.assertion.AssertionSqlGenerator;
import com.adaptivescale.rosetta.test.assertion.DefaultAssertTestEngine;
import com.adaptivescale.rosetta.test.assertion.DefaultSqlExecution;
import com.adaptivescale.rosetta.test.assertion.generator.AssertionSqlGeneratorFactory;
import integration.helpers.GenericJDBCContainer;
import org.junit.Rule;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.Assert.*;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PostgresDDLIntegrationTest {

    private static String IMAGE = "sakiladb/postgres:latest";
    private static String USERNAME = "postgres";
    private static String DATABASE = "sakila";
    private static String SCHEMA = "public";
    private static String PASSWORD = "p_ssW0rd";
    private static String DB_TYPE = "postgres";
    private static String JDBC_URL = "jdbc:postgresql://localhost:{PORT}/sakila";
    private static String CLASS_NAME = "org.postgresql.Driver";
    private static int PORT = 5432;

    private static String DROP_VIEWS = "DROP VIEW actor_info,customer_list,film_list,nicer_but_slower_film_list,sales_by_film_category,sales_by_store,staff_list cascade;";
    private static String DROP_VIEWS1 =
            "CREATE TABLE \"public\".numerics (\n" +
            "\tc_bigint int8 NULL,\n" +
            "\tc_bigserial bigserial NOT NULL,\n" +
            "\tc_bytea bytea NULL,\n" +
            "\tc_boolean bool NULL,\n" +
            "\tc_bool bool NULL,\n" +
            "\tc_integer int4 NULL,\n" +
            "\tc_int int4 NULL,\n" +
            "\tc_interval interval NULL,\n" +
            "\tc_money money NULL,\n" +
            "\tc_numeric numeric NULL,\n" +
            "\tc_real float4 NULL,\n" +
            "\tc_smallint int2 NULL,\n" +
            "\tc_smallserial smallserial NOT NULL,\n" +
            "\tc_serial serial4 NOT NULL,\n" +
            "\tuser_salary money NOT NULL,\n" +
            "\tCONSTRAINT numerics_pkey PRIMARY KEY (user_salary)\n" +
            ");";

    private static String DROP_VIEWS2 =
            "CREATE TABLE \"public\".strings (\n" +
            "\tc_bit bit(1) NULL,\n" +
            "\tc_bitvarying varbit NULL,\n" +
            "\tc_character bpchar(1) NULL,\n" +
            "\tc_charactervarying varchar NULL,\n" +
            "\tc_text text NULL,\n" +
            "\tc_varchar varchar(32) NULL,\n" +
            "\tuser1_name varchar(32) NOT NULL,\n" +
            "\tCONSTRAINT strings_pkey PRIMARY KEY (user1_name)\n" +
            ");";

    private static String DROP_VIEWS3 ="CREATE TABLE \"public\".time_date (\n" +
            "\tc_timestamp timestamp NULL,\n" +
            "\tc_timestamptz timestamptz NULL,\n" +
            "\tc_date date NULL,\n" +
            "\tc_time time NULL,\n" +
            "\tc_timetz timetz NULL,\n" +
            "\tuser_date date NOT NULL,\n" +
            "\tCONSTRAINT time_date_pkey PRIMARY KEY (user_date)\n" +
            ");";

    private static String DROP_VIEWS4 ="CREATE TABLE \"public\".user1 (\n" +
            "\tcustomer_id int4 NULL,\n" +
            "\tuser1_salary money NULL,\n" +
            "\tuser_name varchar(32) NULL,\n" +
            "\tuser_date date NULL\n" +
            ");\n";
    private static String DROP_VIEWSFK =
            "ALTER TABLE \"public\".user1 ADD CONSTRAINT user1_user1_salary_fkey FOREIGN KEY (user1_salary) REFERENCES \"public\".numerics(user_salary);\n" +
            "ALTER TABLE \"public\".user1 ADD CONSTRAINT user1_user_date_fkey FOREIGN KEY (user_date) REFERENCES \"public\".time_date(user_date);\n" +
            "ALTER TABLE \"public\".user1 ADD CONSTRAINT user1_user_name_fkey FOREIGN KEY (user_name) REFERENCES \"public\".strings(user1_name);";
    @Rule
    public static GenericJDBCContainer container = new GenericJDBCContainer(
            IMAGE, USERNAME,PASSWORD, DATABASE, SCHEMA, DB_TYPE, JDBC_URL, CLASS_NAME, PORT).generateContainer();

    @BeforeAll
    public static void beforeAll() {
        container.getContainer().start();
    }

    @Test
    @DisplayName("Prep Postgres SQL")
    @Order(0)
    void prep() throws Exception {
        container.getContainer().createConnection("").createStatement().execute(DROP_VIEWS);
        container.getContainer().createConnection("").createStatement().execute(DROP_VIEWS1);
        container.getContainer().createConnection("").createStatement().execute(DROP_VIEWS2);
        container.getContainer().createConnection("").createStatement().execute(DROP_VIEWS3);
        container.getContainer().createConnection("").createStatement().execute(DROP_VIEWS4);
        container.getContainer().createConnection("").createStatement().execute(DROP_VIEWSFK);
    }

    @Test
    @DisplayName("Test Postgres SQL extract is valid")
    @Order(1)
    void textExtract() throws Exception {
        Database sourceModel = container.getDatabaseModel();
        assertSame("Comparing table count.", 25, sourceModel.getTables().size());
        assertSame("Comparing actor table column count.", 4, container.getTableColumns(sourceModel, "actor").size());
    }

    @Test
    @DisplayName("Test Postgres SQL change finder")
    @Order(2)
    void testDiff() throws Exception {
        Database sourceModel = container.getDatabaseModel();
        ObjectMapper objectMapper = new ObjectMapper();
        Database targetModel = objectMapper.readValue(objectMapper.writeValueAsString(sourceModel), Database.class);
        targetModel.getTables().forEach(table -> {
            if(table.getName().equals("payment")){
                table.setName("payments");
            }
        });
        List<Change<?>> changes = new PostgresChangeFinder().findChanges(sourceModel, targetModel);
        assertSame("Total changes", changes.size(), 2);
        assertSame("Added table", changes.get(0).getStatus().toString(), "ADD");
        assertSame("Dropped table", changes.get(1).getStatus().toString(), "DROP");
    }


    @Test
    @DisplayName("Test Postgres SQL apply changes")
    @Order(3)
    void testApply() throws Exception {
        Database sourceModel = container.getDatabaseModel();
        ObjectMapper objectMapper = new ObjectMapper();
        Database targetModel = objectMapper.readValue(objectMapper.writeValueAsString(sourceModel), Database.class);
        targetModel.getTables().forEach(table -> {
            if(table.getName().equals("actor")){
                table.setName("actors");
            }
        });
        List<Change<?>> changes = DDLFactory.changeFinderForDatabaseType(DB_TYPE).findChanges(targetModel, sourceModel);
        String ddlForChanges = DDLFactory.changeHandler(sourceModel.getDatabaseType()).createDDLForChanges(changes);
        DDLExecutor executor = DDLFactory.executor(container.getRosettaConnection(), new DriverManagerDriverProvider());
        executor.execute(ddlForChanges);
        Database updatedModel = container.getDatabaseModel();
        long actors = updatedModel.getTables().stream().filter(table -> table.getName().equals("actors")).count();
        long actor = updatedModel.getTables().stream().filter(table -> table.getName().equals("actor")).count();
        assertSame("Actors table exists", 1L, actors);
        assertSame("Actor table is removed", 0L, actor);
    }


    @Test
    @DisplayName("Test Postgres SQL Assertion")
    @Order(4)
    void testTest() throws Exception {
        Database sourceModel = container.getDatabaseModel();
        ObjectMapper objectMapper = new ObjectMapper();
        Database targetModel = objectMapper.readValue(objectMapper.writeValueAsString(sourceModel), Database.class);
        targetModel.getTables().forEach(table -> {
            if(table.getName().equals("actor")) {
                table.getColumns().forEach(column -> {
                    if(column.getName().equals("first_name")){
                        AssertTest assertTest = new AssertTest();
                        assertTest.setValue("Nick");
                        assertTest.setOperator("=");
                        assertTest.setExpected("1");
                        Tests tests = new Tests();
                        tests.setAssertions(List.of(assertTest));

                        column.setTests(tests);
                    }
                });
            }
        });

        AssertionSqlGenerator assertionSqlGenerator = AssertionSqlGeneratorFactory.generatorFor(container.getRosettaConnection());
        DefaultSqlExecution defaultSqlExecution = new DefaultSqlExecution(container.getRosettaConnection(), new DriverManagerDriverProvider());
        new DefaultAssertTestEngine(assertionSqlGenerator, defaultSqlExecution).run(container.getRosettaConnection(), targetModel);
    }

    @Test
    @DisplayName("Extract PostgreSQL")
    @Order(5)

    void testExtractDDL() throws Exception {
        Database sourceModel = container.getDatabaseModel();
        ObjectMapper objectMapper = new ObjectMapper();
        Database targetModel = objectMapper.readValue(objectMapper.writeValueAsString(sourceModel), Database.class);
        targetModel.getTables().forEach(table ->{
            if (table.getName().equals("numerics")) {
                table.getColumns().forEach(column -> {
                    switch (column.getName()) {
                        case "c_integer":
                            assertEquals("", "int4", column.getTypeName());
                            break;
                        case "c_bigint":
                            assertEquals("", "int8", column.getTypeName());
                            break;
                        case "c_bigserial":
                            assertEquals("", "bigserial", column.getTypeName());
                            break;
                        case "c_bytea":
                            assertEquals("", "bytea", column.getTypeName());
                            break;
                        case "c_boolean":
                            assertEquals("", "bool", column.getTypeName());
                            break;
                        case "c_bool":
                            assertEquals("", "bool", column.getTypeName());
                            break;
                        case "c_int":
                            assertEquals("", "int4", column.getTypeName());
                            break;
                        case "c_interval":
                            assertEquals("", "interval", column.getTypeName());
                            break;
                        case "c_money":
                            assertEquals("", "money", column.getTypeName());
                            break;
                        case "c_numeric":
                            assertEquals("", "numeric", column.getTypeName());
                            break;
                        case "c_real":
                            assertEquals("", "float4", column.getTypeName());
                            break;
                        case "c_smallint":
                            assertEquals("", "int2", column.getTypeName());
                            break;
                        case "c_smallserial":
                            assertEquals("", "smallserial", column.getTypeName());
                            break;
                        case "c_serial":
                            assertEquals("", "serial", column.getTypeName());
                            break;
                        case "user_salary":
                            assertEquals("", "money", column.getTypeName());
                            break;
                    }
                });
            }
            if(table.getName().equals("strings")) {
                table.getColumns().forEach(column -> {
                    switch (column.getName()) {
                        case "c_bit":
                            assertEquals("", "bit", column.getTypeName());
                            break;
                        case "c_bitvarying":
                            assertEquals("", "varbit", column.getTypeName());
                            break;
                        case "c_character":
                            assertEquals("", "bpchar", column.getTypeName());
                            break;
                        case "c_charactervarying":
                            assertEquals("", "varchar", column.getTypeName());
                            break;
                        case "c_text":
                            assertEquals("", "text", column.getTypeName());
                            break;
                        case "c_varchar":
                            assertEquals("", "varchar", column.getTypeName());
                            break;
                        case "user1_name":
                            assertEquals("", "varchar", column.getTypeName());
                            assertEquals("", true, column.isPrimaryKey());
                            break;
                    }
                });
            }
            if (table.getName().equals("time_date")){
                table.getColumns().forEach(column -> {
                    switch (column.getName()) {
                        case "c_timestamp":
                            assertEquals("", "timestamp", column.getTypeName());
                            break;
                        case "c_timestamptz":
                            assertEquals("", "timestamptz", column.getTypeName());
                            break;
                        case "c_date":
                            assertEquals("", "date", column.getTypeName());
                            break;
                        case "c_time":
                            assertEquals("", "time", column.getTypeName());
                            break;
                        case "c_timetz":
                            assertEquals("", "timetz", column.getTypeName());
                            break;
                        case "user_date":
                            assertEquals("", "date", column.getTypeName());
                            assertEquals("", true, column.isPrimaryKey());
                            break;
                    }
                });
            }
        });

    }


    @Test
    @DisplayName("Foreign key constraint")
    @Order(6)
    void testForeignKey() throws Exception {
        Database sourceModel = container.getDatabaseModel();
        ObjectMapper objectMapper = new ObjectMapper();
        Database targetModel = objectMapper.readValue(objectMapper.writeValueAsString(sourceModel), Database.class);
        targetModel.getTables().forEach(table -> {
            if (table.getName().equals("user1")) {
                table.getColumns().forEach(column -> {
                    switch (column.getName()) {
                        case "user1_salary":
                            assertEquals("", 1L, column.getForeignKeys().size());
                            break;
                        case "user_name":
                            assertEquals("", 1L, column.getForeignKeys().size());
                            break;
                        case "user_date":
                            assertEquals("", 1L, column.getForeignKeys().size());
                            break;
                    }
                });
            }
        });
    }
}
