package com.j11a.argus.feed.poll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.j11a.argus.testsupport.LogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class PollingStartupLoggerTest {

    @Test
    void aFailingCountIsAWarnAndNeverStopsStartup() {
        JdbcClient jdbc = mock(JdbcClient.class);
        when(jdbc.sql(anyString())).thenThrow(new IllegalStateException("db down"));

        try (LogCapture logs = LogCapture.start()) {
            new PollingStartupLogger(jdbc, new PollProperties("0 */15 * * * *", 8, 3)).logPollingSchedule();

            assertThat(logs.at(Level.WARN)).singleElement()
                    .satisfies(event -> assertThat(event.getThrowableProxy()).isNotNull());
            assertThat(logs.at(Level.INFO)).isEmpty();
        }
    }
}
