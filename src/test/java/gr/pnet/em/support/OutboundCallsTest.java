package gr.pnet.em.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import gr.pnet.em.web.CorrelatorFilter;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

class OutboundCallsTest {

    private MockWebServer server;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
        MDC.clear();
    }

    private static OkHttpClient okHttp(String target, boolean hostOnly) {
        return new OkHttpClient.Builder().addInterceptor(OutboundCalls.okHttp(target, hostOnly)).build();
    }

    @Test
    void forwardsTheCorrelatorOnAnOkHttpCall() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201));
        MDC.put(CorrelatorFilter.MDC_KEY, "corr-1");

        okHttp("PCF", false).newCall(new Request.Builder().url(server.url("/npcf-policyauthorization/v1/app-sessions?x=1"))
                .post(okhttp3.RequestBody.create(new byte[0])).build()).execute().close();

        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getHeader(CorrelatorFilter.HEADER)).isEqualTo("corr-1");
    }

    @Test
    void sendsNoCorrelatorWithoutOne() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        okHttp("SCS/AS", true).newCall(new Request.Builder().url(server.url("/client/secret-path/notifications"))
                .post(okhttp3.RequestBody.create(new byte[0])).build()).execute().close();

        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getHeader(CorrelatorFilter.HEADER)).isNull();
    }

    @Test
    void anOkHttpCallWithoutAnAnswerStillFails() throws Exception {
        String url = server.url("/npcf-policyauthorization/v1/app-sessions").toString();
        server.shutdown();

        assertThatThrownBy(() -> okHttp("PCF", false).newCall(new Request.Builder().url(url).build()).execute())
                .isInstanceOf(IOException.class);
    }

    @Test
    void forwardsTheCorrelatorOnARestClientCall() throws Exception {
        server.enqueue(new MockResponse().setBody("{}").setHeader("Content-Type", "application/json"));
        MDC.put(CorrelatorFilter.MDC_KEY, "corr-2");
        URI amf = URI.create(server.url("/ue-info").toString());
        RestClient client = RestClient.builder()
                .requestInterceptor(OutboundCalls.restClient(url -> url.equals(amf) ? "AMF" : "SMF")).build();

        client.get().uri(amf).retrieve().toBodilessEntity();

        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getHeader(CorrelatorFilter.HEADER)).isEqualTo("corr-2");
    }

    @Test
    void aRestClientCallWithoutAnAnswerStillFails() throws Exception {
        URI smf = URI.create(server.url("/pdu-info").toString());
        server.shutdown();
        RestClient client = RestClient.builder().requestInterceptor(OutboundCalls.restClient(url -> "SMF")).build();

        assertThatThrownBy(() -> client.get().uri(smf).retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    void masksPhoneNumbers() {
        assertThat(Masking.maskAll("no subscriber +306912345678 or %2B306912345678"))
                .isEqualTo("no subscriber +3069******78 or %2B3069******78");
        assertThat(Masking.maskAll(null)).isNull();
    }
}
