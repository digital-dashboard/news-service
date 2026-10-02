package com.j11a.argus.security;

import com.j11a.argus.config.ArgusProperties;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final String HEALTH_ENDPOINT = "health";
    private static final String PROMETHEUS_ENDPOINT = "prometheus";

    private static final Set<String> SAFE_METHODS = Set.of(
            HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name());

    private static final RequestMatcher WRITES = request -> !SAFE_METHODS.contains(request.getMethod());

    // CSRF is safe to disable: the API is stateless and authenticates by header key, with no cookies or sessions.
    @SuppressWarnings("java:S4502")
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ArgusProperties properties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        AdminKeyAuthenticationEntryPoint rejection = new AdminKeyAuthenticationEntryPoint(resolver);
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new AdminKeyFilter(properties.admin().key()), AnonymousAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(rejection)
                        .accessDeniedHandler(rejection))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(EndpointRequest.to(HEALTH_ENDPOINT, PROMETHEUS_ENDPOINT)).permitAll()
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).denyAll()
                        .requestMatchers(WRITES)
                        .hasAuthority(AdminKeyFilter.ADMIN_AUTHORITY)
                        // Unknown read routes must reach the 404 problem response, not a 401.
                        .anyRequest().permitAll());
        return http.build();
    }

    /** An empty user store stops Boot generating a password and logging it. */
    @Bean
    UserDetailsService noUsers() {
        return new InMemoryUserDetailsManager();
    }
}
