package com.j11a.argus.config;

import com.j11a.argus.feed.fetch.FetchProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ArgusProperties.class, FetchProperties.class})
public class ArgusConfiguration {
}
