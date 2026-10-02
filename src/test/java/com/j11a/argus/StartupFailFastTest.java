package com.j11a.argus;

import static com.j11a.argus.testsupport.StartupTestSupport.KEY_32;
import static com.j11a.argus.testsupport.StartupTestSupport.causeChain;
import static com.j11a.argus.testsupport.StartupTestSupport.withOverrides;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Full boots only: the property-level rules are covered by ArgusPropertiesTest. */
class StartupFailFastTest {

    private static final String EXCLUDED_AUTO_CONFIGURATIONS = String.join(",",
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
            "org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration");
    private static final List<String> DEFAULTS = List.of(
            "spring.autoconfigure.exclude=" + EXCLUDED_AUTO_CONFIGURATIONS,
            "server.port=0",
            "management.endpoint.health.validate-group-membership=false",
            "argus.db.url=jdbc:postgresql://localhost:5432/argus",
            "argus.db.username=argus",
            "argus.db.password=db-password",
            "argus.admin.key=" + KEY_32);
    private static final String SHORT_KEY = "too-short-admin-key";

    private static void run(String... overrides) {
        new SpringApplicationBuilder(ArgusApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(withOverrides(DEFAULTS, "--", overrides).toArray(String[]::new))
                .close();
    }

    @Test
    void failsFastWithAShortAdminKeyWithoutPrintingIt() {
        assertThatThrownBy(() -> run("argus.admin.key=" + SHORT_KEY))
                .satisfies(failure -> assertThat(causeChain(failure))
                        .contains("argus.admin.key")
                        .doesNotContain(SHORT_KEY));
    }

    @Test
    void failsFastWithoutDatabaseSettings() {
        assertThatThrownBy(() -> run("argus.db.url=", "argus.db.password="))
                .satisfies(failure -> assertThat(causeChain(failure))
                        .contains("argus.db.url")
                        .contains("argus.db.password"));
    }
}
