package app.ziji.auth.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		// 认证用例尚未实现：只开放无敏感信息的健康端点，其余请求默认拒绝。
		http.authorizeHttpRequests(authorize -> authorize
			.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
			.anyRequest().denyAll());
		// Web 使用 Cookie CSRF token；正式会话仍必须采用 HttpOnly refresh cookie。
		http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()));
		return http.build();
	}
}
