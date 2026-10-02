package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class LiquibaseBaselineIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void enableUnaccentChangeSetRanExactlyOnce() {
        Integer runs = jdbc.queryForObject(
                "SELECT count(*) FROM databasechangelog WHERE id = '01-enable-unaccent'", Integer.class);

        assertThat(runs).isEqualTo(1);
    }

    @Test
    void unaccentExtensionIsInstalled() {
        Integer installed = jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'unaccent'", Integer.class);

        assertThat(installed).isEqualTo(1);
    }

    @Test
    void unaccentFoldsDiacritics() {
        String folded = jdbc.queryForObject("SELECT unaccent('Ødegaard')", String.class);

        assertThat(folded).isEqualTo("Odegaard");
    }
}
