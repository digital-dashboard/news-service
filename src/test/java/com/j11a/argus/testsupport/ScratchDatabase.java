package com.j11a.argus.testsupport;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringResourceAccessor;
import org.springframework.core.io.DefaultResourceLoader;
import org.testcontainers.postgresql.PostgreSQLContainer;

public final class ScratchDatabase implements AutoCloseable {

    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String dbName;
    private final String adminUrl;
    private final String scratchUrl;
    private final String username;
    private final String password;

    private ScratchDatabase(String dbName, String adminUrl, String scratchUrl, String username, String password) {
        this.dbName = dbName;
        this.adminUrl = adminUrl;
        this.scratchUrl = scratchUrl;
        this.username = username;
        this.password = password;
    }

    public static ScratchDatabase create(PostgreSQLContainer pg) throws SQLException {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        String dbName = "scratch_" + HexFormat.of().formatHex(bytes);

        String adminUrl = pg.getJdbcUrl();
        String username = pg.getUsername();
        String password = pg.getPassword();

        try (Connection admin = DriverManager.getConnection(adminUrl, username, password);
             Statement stmt = admin.createStatement()) {
            stmt.execute("CREATE DATABASE " + dbName);
        }

        int slashIdx = adminUrl.indexOf('/', "jdbc:postgresql://".length());
        int queryIdx = adminUrl.indexOf('?', slashIdx);
        String scratchUrl = queryIdx == -1
                ? adminUrl.substring(0, slashIdx + 1) + dbName
                : adminUrl.substring(0, slashIdx + 1) + dbName + adminUrl.substring(queryIdx);

        return new ScratchDatabase(dbName, adminUrl, scratchUrl, username, password);
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(scratchUrl, username, password);
    }

    public void migrate(String contexts) throws LiquibaseException, SQLException {
        try (Connection c = connect();
             Liquibase liquibase = new Liquibase(CHANGELOG, new SpringResourceAccessor(new DefaultResourceLoader()), new JdbcConnection(c))) {
            liquibase.update(new Contexts(contexts));
        }
    }

    public void migrateFirst(int changesets, String contexts) throws LiquibaseException, SQLException {
        try (Connection c = connect();
             Liquibase liquibase = new Liquibase(CHANGELOG, new SpringResourceAccessor(new DefaultResourceLoader()), new JdbcConnection(c))) {
            liquibase.update(changesets, new Contexts(contexts), new LabelExpression());
        }
    }

    @Override
    public void close() throws SQLException {
        try (Connection admin = DriverManager.getConnection(adminUrl, username, password);
             Statement stmt = admin.createStatement()) {
            stmt.execute("DROP DATABASE " + dbName + " WITH (FORCE)");
        }
    }
}
