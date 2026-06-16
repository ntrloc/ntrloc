package org.ntrloc.graph;

import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class PostgresTest {

    private JdbcClient jdbcClient;

    public PostgresTest(JdbcClient client) {
       this.jdbcClient = client;

       dropTable();
       createTable();
       try {
           var id = insertItem();
           retrieveItem(id);
           retrieveItemByProperty();
       } catch (Exception e) {
           e.printStackTrace();
       }
    }

    // 1) Drop table if exists
    private void dropTable() {
        jdbcClient
                .sql("DROP TABLE IF EXISTS items")
                .update();
        System.out.println("Dropped table (if existed)");
    }

    // 2) Create items table
    private void createTable() {
        jdbcClient
                .sql("""
                CREATE TABLE items (
                    id                UUID PRIMARY KEY,
                    type              TEXT NOT NULL,
                    visibility_state  TEXT NOT NULL DEFAULT 'NORMAL',
                    properties        JSONB,
                    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """)
                .update();
        System.out.println("Created items table");
    }

    // 3) Insert a record with JSONB properties
    private UUID insertItem() throws Exception {
        UUID id = UUID.randomUUID();

        // JSONB requires PGobject wrapper
        PGobject properties = new PGobject();
        properties.setType("jsonb");
        properties.setValue("""
            {
                "prop1": "Bill",
                "prop2": "Smith",
                "prop3": "PDF",
                "prop4": 49.99
            }
            """);

        jdbcClient
                .sql("""
                INSERT INTO items (id, type, visibility_state, properties)
                VALUES (:id, :type, :visibilityState, :properties)
                """)
                .param("id", id)
                .param("type", "product")
                .param("visibilityState", "NORMAL")
                .param("properties", properties)
                .update();

        System.out.println("Inserted item: " + id);
        return id;
    }

    // 4) Retrieve the record
    private void retrieveItem(UUID id) {
        jdbcClient
                .sql("SELECT * FROM items WHERE id = :id")
                .param("id", id)
                .query()
                .listOfRows()
                .forEach(row -> {
                    System.out.println("id:               " + row.get("id"));
                    System.out.println("type:             " + row.get("type"));
                    System.out.println("visibility_state: " + row.get("visibility_state"));
                    System.out.println("properties:       " + row.get("properties"));
                    System.out.println("created_at:       " + row.get("created_at"));
                });
        System.out.println("Retrieved item: " + id);
    }

    private void retrieveItemByProperty() {
        System.out.println("Retrieving item by property...");
        jdbcClient
                .sql("""
            SELECT *
            FROM items
            WHERE type = :type
              AND properties->>'prop3' = :format
            """)
                .param("type", "product")
                .param("format", "PDF")
                .query()
                .listOfRows()
                .forEach(row -> {
                    System.out.println("id:         " + row.get("id"));
                    System.out.println("properties: " + row.get("properties"));
                });
        System.out.println("Retrieved item!");
    }


}
