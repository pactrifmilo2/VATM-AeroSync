package vatm.aerosync.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import vatm.aerosync.api.security.LegacyTUsersPasswordEncoder;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        PathPatternRequestMatcher.Builder paths =
                PathPatternRequestMatcher.withDefaults();
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/permit-reviews/**",
                                "/api/permit-training-candidates/**",
                                "/api/permit-training-sources/**",
                                "/permit-review-test",
                                "/permit-review-test/**")
                        .authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                paths.matcher("/api/**"))
                        .defaultAuthenticationEntryPointFor(
                                loginEntryPoint(),
                                paths.matcher("/permit-review-test/**"))
                        .defaultAuthenticationEntryPointFor(
                                loginEntryPoint(),
                                paths.matcher("/permit-review-test")))
                .formLogin(form -> form
                        .defaultSuccessUrl("/permit-review-test", true)
                        .permitAll())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new LegacyTUsersPasswordEncoder();
    }

    private AuthenticationEntryPoint loginEntryPoint() {
        return new LoginUrlAuthenticationEntryPoint("/login");
    }
}
