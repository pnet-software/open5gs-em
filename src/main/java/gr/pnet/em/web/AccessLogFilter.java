package gr.pnet.em.web;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import gr.pnet.em.support.Masking;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * One line per request once it's answered, successes and failures alike, as camara-tep's AccessLogFilter: method,
 * path, status, route, duration and the exception behind a problem, with the correlator from the MDC. Never the query
 * string, a body or an exception message, and a phone number in the path is masked. 5xx are logged as WARN,
 * everything else as INFO.
 */
@Component
// Right after CorrelatorFilter, so the correlator is in the MDC
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    private final boolean enabled;
    private final List<PathPattern> excluded;

    public AccessLogFilter(@Value("${em.access-log.enabled:true}") boolean enabled,
            @Value("${em.access-log.exclude:/,/actuator/**,/swagger-ui/**,/v3/api-docs/**,/openapi/**,/favicon.ico}")
            List<String> excluded) {
        this.enabled = enabled;
        this.excluded = excluded.stream().map(String::trim).filter(p -> !p.isEmpty())
                .map(PathPatternParser.defaultInstance::parse)
                .toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        PathContainer path = PathContainer.parsePath(request.getRequestURI());
        return excluded.stream().anyMatch(pattern -> pattern.matches(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            // An exception out of the chain becomes the container's 500
            log(request, failed ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus(),
                    System.nanoTime() - start);
        }
    }

    private static void log(HttpServletRequest request, int status, long nanos) {
        String method = request.getMethod();
        String path = Masking.maskAll(request.getRequestURI());
        long millis = TimeUnit.NANOSECONDS.toMillis(nanos);

        LoggingEventBuilder event = (status >= 500 ? log.atWarn() : log.atInfo())
                .addKeyValue("http.request.method", method)
                .addKeyValue("url.path", path)
                .addKeyValue("http.response.status_code", status)
                .addKeyValue("event.duration", nanos);
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (route != null) {
            event = event.addKeyValue("http.route", route.toString());
        }
        String type = RequestOutcome.type(request);
        if (type != null) {
            event = event.addKeyValue("error.type", type);
        }
        event.log("{} {} {} {}ms", method, path, status, millis);
    }
}
