package com.j11a.argus.integration;

import com.j11a.argus.testsupport.FeedStubServer;
import java.io.IOException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class FeedStubConfig {

    @Bean
    FeedStubServer feedStubServer() throws IOException {
        return new FeedStubServer();
    }
}
