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

    @Test
    void contentHashAndRekeyChangeSetsRanExactlyOnce() {
        Integer runs07 = jdbc.queryForObject(
                "SELECT count(*) FROM databasechangelog WHERE id = '07-add-content-hash'", Integer.class);
        assertThat(runs07).isEqualTo(1);

        Integer runs08 = jdbc.queryForObject(
                "SELECT count(*) FROM databasechangelog WHERE id = '08-rekey-articles'", Integer.class);
        assertThat(runs08).isEqualTo(1);
    }

    @Test
    void seedSourcesAndFeedsIsAbsentUnderTestContext() {
        Integer runs09 = jdbc.queryForObject(
                "SELECT count(*) FROM databasechangelog WHERE id = '09-seed-sources-and-feeds'", Integer.class);
        assertThat(runs09).isZero();
    }
}
