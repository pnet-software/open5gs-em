package gr.pnet.em.tep.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import gr.pnet.em.config.Open5gsProperties;
import gr.pnet.em.error.UpstreamException;
import gr.pnet.em.support.OutboundCalls;

/** Reads the UE state Open5GS exposes on its AMF (ue-info) and SMF (pdu-info) metrics endpoints. */
@Component
public class Open5gsMetricsClient {

    private final RestClient restClient;
    private final URI amfUrl;
    private final URI smfUrl;

    public Open5gsMetricsClient(RestClient.Builder restClientBuilder, Open5gsProperties properties) {
        Open5gsProperties.Metrics metrics = properties.metrics();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(metrics.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(metrics.readTimeout());
        this.amfUrl = metrics.amfUrl();
        this.smfUrl = metrics.smfUrl();
        this.restClient = restClientBuilder.requestFactory(requestFactory)
                .requestInterceptor(OutboundCalls.restClient(url -> url.equals(amfUrl) ? "AMF" : "SMF"))
                .build();
    }

    public List<UeContexts.UeContext> ueContexts() {
        return items(get(amfUrl, UeContexts.class, "AMF"), UeContexts::items);
    }

    public List<UeSessions.UeSession> ueSessions() {
        return items(get(smfUrl, UeSessions.class, "SMF"), UeSessions::items);
    }

    private <T> T get(URI url, Class<T> type, String function) {
        try {
            return restClient.get().uri(url).retrieve().body(type);
        } catch (RestClientException e) {
            throw new UpstreamException("Open5GS " + function + " metrics unavailable at " + url, e);
        }
    }

    private static <T, I> List<I> items(T response, Function<T, List<I>> items) {
        return Optional.ofNullable(response).map(items).orElse(List.of());
    }
}
