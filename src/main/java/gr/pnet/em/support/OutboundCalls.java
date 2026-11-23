package gr.pnet.em.support;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import gr.pnet.em.web.CorrelatorFilter;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * One log line per call this app makes (logger {@code gr.pnet.em.OutboundCalls}), as camara-tep logs its TEP calls:
 * the target (AMF, SMF, PCF, SCS/AS), method, path, status and duration. Never the query string or a body; an SCS/AS
 * is logged by its host only, since its notification URL is the client's. A call that gets no answer (connect or read
 * timeout, refused) is a WARN. Every call also carries the request's x-correlator, so the target can log it too.
 * <p>
 * The fields are {@code call.*}, as camara-tep's are {@code tep.*}: ECS's {@code service} and {@code http} objects are
 * already written for this app and its own request, and a second {@code service} would fail the whole line.
 */
public final class OutboundCalls {

    private static final Logger calls = LoggerFactory.getLogger("gr.pnet.em.OutboundCalls");

    private OutboundCalls() {
    }

    /** For a RestClient; {@code target} names the function a URL belongs to. */
    public static ClientHttpRequestInterceptor restClient(Function<URI, String> target) {
        return (request, body, execution) -> {
            String correlator = MDC.get(CorrelatorFilter.MDC_KEY);
            if (correlator != null && !request.getHeaders().containsHeader(CorrelatorFilter.HEADER)) {
                request.getHeaders().set(CorrelatorFilter.HEADER, correlator);
            }
            String name = target.apply(request.getURI());
            String method = request.getMethod().name();
            String path = Masking.maskAll(request.getURI().getRawPath());
            long start = System.nanoTime();
            try {
                ClientHttpResponse response = execution.execute(request, body);
                answered(name, method, path, response.getStatusCode().value(), System.nanoTime() - start);
                return response;
            } catch (IOException e) {
                failed(name, method, path, e, System.nanoTime() - start);
                throw e;
            }
        };
    }

    /** For an OkHttp client; {@code hostOnly} logs the host instead of the path. */
    public static Interceptor okHttp(String target, boolean hostOnly) {
        return chain -> {
            Request request = chain.request();
            String correlator = MDC.get(CorrelatorFilter.MDC_KEY);
            if (correlator != null && request.header(CorrelatorFilter.HEADER) == null) {
                request = request.newBuilder().header(CorrelatorFilter.HEADER, correlator).build();
            }
            String where = hostOnly ? request.url().host() : Masking.maskAll(request.url().encodedPath());
            long start = System.nanoTime();
            try {
                Response response = chain.proceed(request);
                answered(target, request.method(), where, response.code(), System.nanoTime() - start);
                return response;
            } catch (IOException e) {
                failed(target, request.method(), where, e, System.nanoTime() - start);
                throw e;
            }
        };
    }

    private static void answered(String target, String method, String where, int status, long nanos) {
        calls.atInfo()
                .addKeyValue("call.target", target)
                .addKeyValue("call.method", method)
                .addKeyValue("call.status_code", status)
                .addKeyValue("event.duration", nanos)
                .log("{} {} {} {} {}ms", target, method, where, status, TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    private static void failed(String target, String method, String where, IOException e, long nanos) {
        calls.atWarn()
                .addKeyValue("call.target", target)
                .addKeyValue("call.method", method)
                .addKeyValue("error.type", e.getClass().getSimpleName())
                .addKeyValue("event.duration", nanos)
                .log("{} {} {} failed: {} after {}ms", target, method, where, e.getClass().getSimpleName(),
                        TimeUnit.NANOSECONDS.toMillis(nanos));
    }
}
