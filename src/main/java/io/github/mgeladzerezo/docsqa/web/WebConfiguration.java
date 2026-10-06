package io.github.mgeladzerezo.docsqa.web;

import io.github.mgeladzerezo.docsqa.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Applies the rate limits to the two expensive endpoints: asking a question and uploading a file.
 * Clients are identified by their socket address. Behind a reverse proxy that is the proxy's address,
 * so a real deployment would trust a forwarded-for header from that proxy only.
 */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    private final RateLimiter chatLimiter;
    private final RateLimiter uploadLimiter;

    public WebConfiguration(AppProperties properties) {
        this.chatLimiter = new RateLimiter(properties.rateLimit().chatPerMinute(), System::nanoTime);
        this.uploadLimiter = new RateLimiter(properties.rateLimit().uploadPerMinute(), System::nanoTime);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(limit(chatLimiter)).addPathPatterns("/api/chat", "/api/search");
        registry.addInterceptor(limit(uploadLimiter)).addPathPatterns("/api/documents");
    }

    private static HandlerInterceptor limit(RateLimiter limiter) {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws IOException {
                if (!"POST".equals(request.getMethod())) {
                    return true;
                }
                long retryAfter = limiter.tryAcquire(request.getRemoteAddr());
                if (retryAfter == 0) {
                    return true;
                }
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                response.getWriter().write(
                        "{\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\"Rate limit exceeded. Retry in %d second(s).\"}"
                                .formatted(retryAfter));
                return false;
            }
        };
    }
}
