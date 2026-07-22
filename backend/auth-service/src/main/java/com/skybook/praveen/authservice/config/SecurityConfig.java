package com.skybook.praveen.authservice.config;

import com.skybook.praveen.authservice.security.JwtAuthenticationFilter;
import com.skybook.praveen.authservice.security.ServiceClientDetailsService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Two ordered filter chains (SECURITY_HARDENING_MODULE.md §3.3, review 3):
 *
 * <ol>
 *   <li><b>@Order(1) — client-credential chain</b> for
 *       {@code /api/auth/service-token}: HTTP Basic against the
 *       service-client registry (BCrypt), no JWT. A machine caller obtaining
 *       its first {@code ROLE_SERVICE} token cannot already present one, so
 *       this endpoint must not sit behind the JWT filter.</li>
 *   <li><b>@Order(2) — application chain</b>: register/login public, everything
 *       else needs a valid RS256 user token via the JWT filter.</li>
 * </ol>
 *
 * Spring Security runs only the first chain whose {@code securityMatcher}
 * matches, so ordering + matcher keep the two authentication styles cleanly
 * separated.
 */
@Configuration
public class SecurityConfig {

    /**
     * The JWT filter is a {@code @Component OncePerRequestFilter}, which Spring
     * Boot would otherwise auto-register in the servlet container so it runs on
     * EVERY request - including the {@code @Order(1)} client-credential chain
     * (SECURITY_HARDENING_MODULE.md §3.3, review 4). Disabling the container
     * registration confines it to the application chain, where it is added
     * explicitly below.
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain serviceTokenFilterChain(HttpSecurity http,
                                                       ServiceClientDetailsService clientDetailsService,
                                                       PasswordEncoder passwordEncoder) throws Exception {

        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(clientDetailsService);
        provider.setPasswordEncoder(passwordEncoder);

        // Authentication failures on this Basic-auth endpoint must return 401,
        // not 403 (SECURITY_HARDENING_MODULE.md §6). Without an explicit entry
        // point, an anonymous request tripping .authenticated() surfaces as an
        // access-denied 403; a bad/unknown/missing credential all now return an
        // identical, indistinguishable 401 (no client enumeration, §3.3).
        AuthenticationEntryPoint entryPoint = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);

        http
                .securityMatcher("/api/auth/service-token")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .authenticationProvider(provider)
                .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint));

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain applicationFilterChain(HttpSecurity http,
                                                      JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
