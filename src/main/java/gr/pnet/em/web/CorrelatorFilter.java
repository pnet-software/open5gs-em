package gr.pnet.em.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The {@code x-correlator} of every request, as camara-tep's CorrelatorFilter handles it: echoed on the response,
 * error responses included, and in the MDC, so every log line of the request carries it. Without a valid one, the
 * logs get a generated one and the response none. This covers the 3GPP AsSessionWithQoS operations too, whose spec
 * doesn't declare the header; the TEP operations declare it, so they reject an invalid one with a 400.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelatorFilter extends OncePerRequestFilter {

    public static final String HEADER = "x-correlator";
    public static final String MDC_KEY = "correlator";

    // The XCorrelator schema of the TEP spec, as in CAMARA
    private static final Pattern VALID = Pattern.compile("^[a-zA-Z0-9-_:;./<>{}]{0,256}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlator = request.getHeader(HEADER);
        boolean valid = correlator != null && VALID.matcher(correlator).matches();
        if (valid) {
            // Set before the chain runs, so it survives a response that is committed with an error
            response.setHeader(HEADER, correlator);
        }
        MDC.put(MDC_KEY, valid ? correlator : UUID.randomUUID().toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
