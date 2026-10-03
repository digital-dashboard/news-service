package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.testsupport.ScratchDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

class ScratchDatabaseIT extends AbstractIntegrationTest {

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    void migrateFirstAppliesExactNumberOfChangesets() throws Exception {
        try (ScratchDatabase db = ScratchDatabase.create(postgres)) {
            db.migrateFirst(6, "test");
            try (Connection conn = db.connect();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM databasechangelog")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(6);
            }
        }
    }
}
