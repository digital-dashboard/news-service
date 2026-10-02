package com.j11a.argus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

class StartupFailFastTest {

    private static final String EXCLUDED_AUTO_CONFIGURATIONS = String.join(",",
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
            "org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration");
    private static final String KEY_32 = "0123456789abcdef0123456789abcdef";
    private static final String SHORT_KEY = "too-short-admin-key";

    private static List<String> arguments(String... overrides) {
        List<String> arguments = new ArrayList<>(List.of(
                "--spring.autoconfigure.exclude=" + EXCLUDED_AUTO_CONFIGURATIONS,
                "--server.port=0",
                "--management.endpoint.health.validate-group-membership=false",
                "--argus.db.url=jdbc:postgresql://localhost:5432/argus",
                "--argus.db.username=argus",
                "--argus.db.password=db-password",
                "--argus.admin.key=" + KEY_32));
        for (String override : overrides) {
            String name = override.substring(0, override.indexOf('='));
            arguments.removeIf(argument -> argument.startsWith("--" + name + "="));
            arguments.add("--" + override);
        }
        return arguments;
    }

    private static void run(String... overrides) {
        new SpringApplicationBuilder(ArgusApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(arguments(overrides).toArray(String[]::new))
                .close();
    }

    private static String chainedMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }

    @Test
    void startsWhenEverythingIsConfigured() {
        run();
    }

    @Test
    void failsFastWithoutTheAdminKey() {
        assertThatThrownBy(() -> run("argus.admin.key="))
                .satisfies(failure -> assertThat(chainedMessages(failure)).contains("argus.admin.key"));
    }

    @Test
    void failsFastWithAShortAdminKeyWithoutPrintingIt() {
        assertThatThrownBy(() -> run("argus.admin.key=" + SHORT_KEY))
                .satisfies(failure -> assertThat(chainedMessages(failure))
                        .contains("argus.admin.key")
                        .doesNotContain(SHORT_KEY));
    }

    @Test
    void failsFastWithoutDatabaseSettings() {
        assertThatThrownBy(() -> run("argus.db.url=", "argus.db.password="))
                .satisfies(failure -> assertThat(chainedMessages(failure))
                        .contains("argus.db.url")
                        .contains("argus.db.password"));
    }
}
