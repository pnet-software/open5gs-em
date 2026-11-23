package gr.pnet.em.nef.pcf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import gr.pnet.em.config.Open5gsProperties;
import gr.pnet.em.error.UpstreamException;
import gr.pnet.em.generated.tep.model.AsSessionWithQoSSubscription;
import gr.pnet.em.generated.tep.model.FlowInfo;
import gr.pnet.em.generated.tep.model.TepQualityProfile;
import gr.pnet.em.generated.tep.model.TepQualityProfileRate;
import okhttp3.Protocol;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PcfPolicyAuthorizationClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MockWebServer pcfServer;
    private PcfPolicyAuthorizationClient client;

    @BeforeEach
    void startPcf() throws IOException {
        pcfServer = new MockWebServer();
        // As the Open5GS SBI: cleartext HTTP/2 without an upgrade
        pcfServer.setProtocols(List.of(Protocol.H2_PRIOR_KNOWLEDGE));
        pcfServer.start();
        client = client(URI.create(pcfServer.url("/npcf-policyauthorization/v1").toString()));
    }

    @AfterEach
    void stopPcf() throws IOException {
        pcfServer.shutdown();
    }

    private static PcfPolicyAuthorizationClient client(URI pcfBaseUrl) {
        Open5gsProperties properties = new Open5gsProperties(
                new Open5gsProperties.Pcf(pcfBaseUrl, URI.create("http://em:8080/notifications")),
                new Open5gsProperties.Metrics(URI.create("http://amf"), URI.create("http://smf"),
                        Duration.ofSeconds(1), Duration.ofSeconds(1)));
        PcfClientConfiguration configuration = new PcfClientConfiguration();
        var apiClient = configuration.pcfApiClient(properties);
        return new PcfPolicyAuthorizationClient(configuration.pcfAppSessionsApi(apiClient),
                configuration.pcfAppSessionApi(apiClient), properties);
    }


    private static TepQualityProfileRate mbps(int value) {
        return new TepQualityProfileRate(value, TepQualityProfileRate.UnitEnum.MBPS);
    }

    private static TepQualityProfile profile(TepQualityProfile.PreemptionCapabilityEnum preemptionCapability,
            TepQualityProfile.PreemptionVulnerabilityEnum preemptionVulnerability) {
        return new TepQualityProfile("QOS_S", TepQualityProfile.StatusEnum.ACTIVE)
                .targetMinUpstreamRate(mbps(1))
                .maxUpstreamRate(mbps(5))
                .targetMinDownstreamRate(mbps(2))
                .maxDownstreamRate(mbps(10))
                .priority(20)
                .preemptionCapability(preemptionCapability)
                .preemptionVulnerability(preemptionVulnerability);
    }

    private static TepQualityProfile profile() {
        return profile(null, null);
    }

    private static AsSessionWithQoSSubscription subscription() {
        AsSessionWithQoSSubscription subscription = new AsSessionWithQoSSubscription("http://scs-as/callback");
        subscription.setSupportedFeatures("12");
        subscription.setUeIpv4Addr("10.45.0.13");
        FlowInfo flow = new FlowInfo(0);
        flow.setFlowDescriptions(List.of("permit in ip from 10.45.0.13 any to 0.0.0.0/0 any",
                "permit out ip from 0.0.0.0/0 any to 10.45.0.13 any"));
        subscription.setFlowInfo(List.of(flow));
        return subscription;
    }

    private static MockResponse created(String location) {
        MockResponse response = new MockResponse().setResponseCode(201)
                .setHeader("Content-Type", "application/json").setBody("{}");
        return location == null ? response : response.setHeader("Location", location);
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        return pcfServer.takeRequest(5, TimeUnit.SECONDS);
    }

    @Test
    void createsAnAppSessionWithTheProfilesMediaComponent() throws Exception {
        String location = pcfServer.url("/npcf-policyauthorization/v1/app-sessions/42").toString();
        pcfServer.enqueue(created(location));

        String created = client.createAppSession("as1", "s1", subscription(), profile(TepQualityProfile.PreemptionCapabilityEnum.MAY_PREEMPT,
                TepQualityProfile.PreemptionVulnerabilityEnum.NOT_PREEMPTABLE));

        assertThat(created).isEqualTo(location);
        RecordedRequest request = takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/npcf-policyauthorization/v1/app-sessions");
        JsonNode reqData = JSON.readTree(request.getBody().readUtf8()).get("ascReqData");
        assertThat(reqData.get("notifUri").asString()).isEqualTo("http://em:8080/notifications/as1/s1");
        assertThat(reqData.get("suppFeat").asString()).isEqualTo("12");
        assertThat(reqData.get("ueIpv4").asString()).isEqualTo("10.45.0.13");
        JsonNode component = reqData.get("medComponents").get("1");
        assertThat(component.get("qosReference").asString()).isEqualTo("QOS_S");
        assertThat(component.has("medType")).isFalse();
        assertThat(component.get("marBwDl").asString()).isEqualTo("10 Mbps");
        assertThat(component.get("marBwUl").asString()).isEqualTo("5 Mbps");
        assertThat(component.get("mirBwDl").asString()).isEqualTo("2 Mbps");
        assertThat(component.get("mirBwUl").asString()).isEqualTo("1 Mbps");
        assertThat(component.get("resPrio").asString()).isEqualTo("PRIO_13");
        assertThat(component.get("preemptCap").asString()).isEqualTo("MAY_PREEMPT");
        assertThat(component.get("preemptVuln").asString()).isEqualTo("NOT_PREEMPTABLE");
        assertThat(component.get("fStatus").asString()).isEqualTo("ENABLED");
        JsonNode subComponent = component.get("medSubComps").get("0");
        assertThat(subComponent.get("fNum").asInt()).isZero();
        assertThat(subComponent.get("fDescs")).hasSize(2);
    }

    @Test
    void aProfileWithoutPreemptionStillNamesItsQos() throws Exception {
        pcfServer.enqueue(created("/app-sessions/1"));

        client.createAppSession("as1", "s1", subscription(), profile());

        JsonNode component = JSON.readTree(takeRequest().getBody().readUtf8()).at("/ascReqData/medComponents/1");
        assertThat(component.get("qosReference").asString()).isEqualTo("QOS_S");
        assertThat(component.has("preemptCap")).isFalse();
        assertThat(component.has("preemptVuln")).isFalse();
    }

    @Test
    void anAppSessionWithoutALocationIsAnError() {
        pcfServer.enqueue(created(null));

        assertThatThrownBy(() -> client.createAppSession("as1", "s1", subscription(),
                profile()))
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("no Location");
    }

    @Test
    void aRejectedAppSessionIsAnError() {
        pcfServer.enqueue(new MockResponse().setResponseCode(403).setBody("{\"cause\":\"REQUESTED_SERVICE_NOT_AUTHORIZED\"}"));

        assertThatThrownBy(() -> client.createAppSession("as1", "s1", subscription(),
                profile()))
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("REQUESTED_SERVICE_NOT_AUTHORIZED");
    }

    @Test
    void anUnreachablePcfIsAnError() throws IOException {
        URI baseUrl = URI.create(pcfServer.url("/npcf-policyauthorization/v1").toString());
        pcfServer.shutdown();

        assertThatThrownBy(() -> client(baseUrl).createAppSession("as1", "s1", subscription(),
                profile()))
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    void deletesAnAppSession() throws Exception {
        pcfServer.enqueue(new MockResponse().setResponseCode(204));

        client.deleteAppSession("42");

        RecordedRequest request = takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/npcf-policyauthorization/v1/app-sessions/42/delete");
    }

    @Test
    void anAppSessionThePcfNoLongerKnowsCountsAsDeleted() {
        pcfServer.enqueue(new MockResponse().setResponseCode(404));

        assertThatCode(() -> client.deleteAppSession("42")).doesNotThrowAnyException();
    }

    @Test
    void aFailedDeleteIsAnError() {
        pcfServer.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        assertThatThrownBy(() -> client.deleteAppSession("42"))
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("500");
    }

    @Test
    void updatesAnAppSessionWithTheWholeMediaComponent() throws Exception {
        // Open5GS answers with the patch it applied, which isn't an AppSessionContext
        pcfServer.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"ascReqData\":{\"medComponents\":{\"1\":{\"medCompN\":1}}}}"));

        client.updateAppSession("42", subscription(), profile(TepQualityProfile.PreemptionCapabilityEnum.MAY_PREEMPT,
                TepQualityProfile.PreemptionVulnerabilityEnum.NOT_PREEMPTABLE));

        RecordedRequest request = takeRequest();
        assertThat(request.getMethod()).isEqualTo("PATCH");
        assertThat(request.getPath()).isEqualTo("/npcf-policyauthorization/v1/app-sessions/42");
        assertThat(request.getHeader("Content-Type")).startsWith("application/merge-patch+json");
        JsonNode reqData = JSON.readTree(request.getBody().readUtf8()).get("ascReqData");
        // Only the media component: the session's other data stays as created
        assertThat(reqData.propertyNames()).containsExactly("medComponents");
        JsonNode component = reqData.at("/medComponents/1");
        assertThat(component.get("medCompN").asInt()).isEqualTo(1);
        assertThat(component.get("qosReference").asString()).isEqualTo("QOS_S");
        assertThat(component.get("marBwDl").asString()).isEqualTo("10 Mbps");
        assertThat(component.get("mirBwUl").asString()).isEqualTo("1 Mbps");
        assertThat(component.get("resPrio").asString()).isEqualTo("PRIO_13");
        assertThat(component.get("preemptCap").asString()).isEqualTo("MAY_PREEMPT");
        assertThat(component.get("preemptVuln").asString()).isEqualTo("NOT_PREEMPTABLE");
        assertThat(component.has("codecs")).isFalse();
        // The flows every time: the PCF takes a component without them as one whose flows are gone
        JsonNode subComponent = component.at("/medSubComps/0");
        assertThat(subComponent.get("fNum").asInt()).isZero();
        assertThat(subComponent.get("fDescs")).hasSize(2);
    }

    @Test
    void aRejectedUpdateIsAnError() {
        pcfServer.enqueue(new MockResponse().setResponseCode(403).setBody("{\"cause\":\"No PCC Rule in DB\"}"));

        assertThatThrownBy(() -> client.updateAppSession("42", subscription(), profile()))
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("update app session 42")
                .hasMessageContaining("403");
    }
}
