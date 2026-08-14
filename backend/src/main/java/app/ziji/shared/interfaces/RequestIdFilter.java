package app.ziji.shared.interfaces;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
final class RequestIdFilter extends OncePerRequestFilter {

	static final String HEADER = "X-Request-ID";
	static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		// 只接受格式安全且长度受限的上游 ID，其他情况生成服务端 UUID。
		String requestId = normalize(request.getHeader(HEADER));
		request.setAttribute(ATTRIBUTE, requestId);
		response.setHeader(HEADER, requestId);
		try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", requestId)) {
			chain.doFilter(request, response);
		}
	}

	private String normalize(String candidate) {
		if (candidate != null && candidate.matches("[A-Za-z0-9._:-]{1,100}")) {
			return candidate;
		}
		return UUID.randomUUID().toString();
	}
}
