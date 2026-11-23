package gr.pnet.em.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AS sessions with QoS end to end: TEP quality profile → /tep-api/v1/3gpp-as-session-with-qos → the UE's PCC rule →
 * PCF → SCS/AS. The UE is the SMF fixture's: imsi-001010000000001, 10.45.0.7 on DNN internet in slice 1/ffffff,
 * which is its subscription's slice 1 without an sd.
 */
class QosSessionIT extends IntegrationTest {

    private static final String SUBSCRIPTIONS = "/tep-api/v1/3gpp-as-session-with-qos/v1/as1/subscriptions";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MERGE_PATCH = "application/merge-patch+json";

    private MockWebServer scsAs;

    @BeforeEach
    void startScsAs() throws IOException {
        scsAs = new MockWebServer();
        scsAs.start();
    }

    @BeforeEach
    void connectTheUe() throws Exception {
        mvc.perform(post("/tep-api/v1/subscriber").contentType(MediaType.APPLICATION_JSON).content("""
                {"imsi":"001010000000001","phoneNumber":"306900000001","authenticationManagementField":"8000",
                 "permanentKeyValue":"465B5CE8B199B49FAA5F0A2EE238A6BC","opcValue":"E8ED289DEBA952E4283B54E88E6183CA",
                 "dataNetworks":[{"dnn":"internet","sst":"1"}]}"""))
                .andExpect(status().isCreated());
        METRICS.items("/pdu-info", fixture("ue-session.json"));
    }

    /** The 5QIs the PCC rules of the UE's session are for. */
    private List<Integer> pccRule5qis() {
        Document subscriber = mongo.findOne(new Query(Criteria.where("imsi").is("001010000000001")), Document.class,
                "subscribers");
        Document session = subscriber.getList("slice", Document.class).get(0).getList("session", Document.class).get(0);
        return session.getList("pcc_rule", Document.class).stream()
                .map(rule -> rule.get("qos", Document.class).getInteger("index"))
                .toList();
    }

    @AfterEach
    void stopScsAs() throws IOException {
        scsAs.shutdown();
    }

    private String subscription(String qosReference) {
        return subscription(qosReference, "10.45.0.7");
    }

    private String subscription(String qosReference, String ueIpv4Addr) {
        return """
                {"supportedFeatures":"12","notificationDestination":"%s","qosReference":"%s","ueIpv4Addr":"%s",
                 "flowInfo":[{"flowId":0,"flowDescriptions":["permit in ip from %s any to 0.0.0.0/0 any",
                   "permit out ip from 0.0.0.0/0 any to %s any"]}]}"""
                .formatted(scsAs.url("/callback/v0"), qosReference, ueIpv4Addr, ueIpv4Addr, ueIpv4Addr);
    }

    /** Creates a session with the configured QOS_S and returns its id. */
    private String createSession() throws Exception {
        String location = mvc.perform(post(SUBSCRIPTIONS).contentType(MediaType.APPLICATION_JSON)
                .content(subscription("QOS_S")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).startsWith("http://localhost" + SUBSCRIPTIONS + "/");
        return location.substring(location.lastIndexOf('/') + 1);
    }

    @Test
    void runsASessionThroughThePcf() throws Exception {
        assertThat(pccRule5qis()).isEmpty();

        String id = createSession();

        // QOS_S's 5QI, which the PCF needs a PCC rule for
        assertThat(pccRule5qis()).containsExactly(67);

        RecordedRequest appSession = takePcfRequest();
        assertThat(appSession.getPath()).isEqualTo("/npcf-policyauthorization/v1/app-sessions");
        JsonNode reqData = JSON.readTree(appSession.getBody().readUtf8()).get("ascReqData");
        assertThat(reqData.get("notifUri").asString()).isEqualTo(PCF_NOTIFICATIONS + "/as1/" + id);
        assertThat(reqData.at("/medComponents/1/qosReference").asString()).isEqualTo("QOS_S");
        JsonNode component = reqData.at("/medComponents/1");
        assertThat(component.get("marBwDl").asString()).isEqualTo("10 Mbps");
        assertThat(component.get("mirBwDl").asString()).isEqualTo("2 Mbps");
        assertThat(component.has("mirBwUl")).isFalse();
        assertThat(component.get("resPrio").asString()).isEqualTo("PRIO_13");
        assertThat(component.get("preemptVuln").asString()).isEqualTo("NOT_PREEMPTABLE");

        Document stored = mongo.findById(id, Document.class, "qos_sessions");
        String appSessionId = stored.getString("appSessionId");
        assertThat(stored.getString("appSessionUri")).endsWith("/app-sessions/" + appSessionId);
        assertThat(stored.getString("scsAsId")).isEqualTo("as1");

        mvc.perform(get(SUBSCRIPTIONS + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.self").value("http://localhost" + SUBSCRIPTIONS + "/" + id))
                .andExpect(jsonPath("$.qosReference").value("QOS_S"));
        mvc.perform(get(SUBSCRIPTIONS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/tep-api/v1/3gpp-as-session-with-qos/v1/as2/subscriptions"))
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(delete(SUBSCRIPTIONS + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction").value(id))
                .andExpect(jsonPath("$.eventReports[0].event").value("SESSION_TERMINATION"));
        assertThat(takePcfRequest().getPath())
                .isEqualTo("/npcf-policyauthorization/v1/app-sessions/" + appSessionId + "/delete");
        assertThat(mongo.findById(id, Document.class, "qos_sessions")).isNull();
    }

    @Test
    void changesASessionInPlaceOrOnANewAppSessionForAnother5qi() throws Exception {
        String id = createSession();
        takePcfRequest();
        String firstAppSession = mongo.findById(id, Document.class, "qos_sessions").getString("appSessionId");

        // New flows with the same profile: the app session changes in place, with the whole media component
        mvc.perform(patch(SUBSCRIPTIONS + "/" + id).contentType(MERGE_PATCH).content("""
                {"flowInfo":[{"flowId":1,"flowDescriptions":["permit out ip from 0.0.0.0/0 80 to 10.45.0.7 any"]}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qosReference").value("QOS_S"))
                .andExpect(jsonPath("$.flowInfo[0].flowId").value(1));
        RecordedRequest update = takePcfRequest();
        assertThat(update.getMethod()).isEqualTo("PATCH");
        assertThat(update.getPath()).isEqualTo("/npcf-policyauthorization/v1/app-sessions/" + firstAppSession);
        JsonNode component = JSON.readTree(update.getBody().readUtf8()).at("/ascReqData/medComponents/1");
        assertThat(component.get("qosReference").asString()).isEqualTo("QOS_S");
        assertThat(component.get("marBwDl").asString()).isEqualTo("10 Mbps");
        assertThat(component.at("/medSubComps/1/fDescs/0").asString())
                .isEqualTo("permit out ip from 0.0.0.0/0 80 to 10.45.0.7 any");

        // QOS_M has 5QI 2: a new app session first, then the old one deleted, and the session keeps its id and flows
        mvc.perform(patch(SUBSCRIPTIONS + "/" + id).contentType(MERGE_PATCH).content("{\"qosReference\":\"QOS_M\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qosReference").value("QOS_M"))
                .andExpect(jsonPath("$.flowInfo[0].flowId").value(1));
        RecordedRequest created = takePcfRequest();
        assertThat(created.getPath()).isEqualTo("/npcf-policyauthorization/v1/app-sessions");
        JsonNode reqData = JSON.readTree(created.getBody().readUtf8()).get("ascReqData");
        assertThat(reqData.get("notifUri").asString()).isEqualTo(PCF_NOTIFICATIONS + "/as1/" + id);
        assertThat(reqData.at("/medComponents/1/qosReference").asString()).isEqualTo("QOS_M");
        assertThat(takePcfRequest().getPath())
                .isEqualTo("/npcf-policyauthorization/v1/app-sessions/" + firstAppSession + "/delete");
        Document stored = mongo.findById(id, Document.class, "qos_sessions");
        assertThat(stored.getString("appSessionId")).isNotEqualTo(firstAppSession);
        assertThat(stored.getInteger("fiveQi")).isEqualTo(2);
        assertThat(pccRule5qis()).containsExactly(67, 2);

        // The PCF reporting the end of the old app session doesn't end the session
        mvc.perform(post("/notifications/as1/" + id + "/terminate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"resUri\":\"%s\",\"termCause\":\"PDU_SESSION_TERMINATION\"}"
                        .formatted(PCF.url("/npcf-policyauthorization/v1/app-sessions/" + firstAppSession))))
                .andExpect(status().isNoContent());
        mvc.perform(get(SUBSCRIPTIONS + "/" + id)).andExpect(status().isOk());
    }

    @Test
    void aChangeTepCanNotApplyIsRejectedBeforeThePcf() throws Exception {
        String id = createSession();
        takePcfRequest();

        mvc.perform(patch(SUBSCRIPTIONS + "/" + id).contentType(MERGE_PATCH)
                .content("{\"qosReference\":\"QOS_M\",\"disUeNotif\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.invalidParams[0].param").value("/disUeNotif"));
        mvc.perform(patch(SUBSCRIPTIONS + "/" + id).contentType(MERGE_PATCH).content("{\"qosReference\":\"QOS_D\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.invalidParams[0].param").value("/qosReference"));

        assertThat(PCF.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
        mvc.perform(get(SUBSCRIPTIONS + "/" + id)).andExpect(jsonPath("$.qosReference").value("QOS_S"));
    }

    @Test
    void aSecondSessionUsesThePccRuleTheFirstAdded() throws Exception {
        createSession();
        createSession();

        assertThat(pccRule5qis()).containsExactly(67);
    }

    @Test
    void aUeWithoutAPduSessionIsRejectedBeforeThePcf() throws Exception {
        mvc.perform(post(SUBSCRIPTIONS).contentType(MediaType.APPLICATION_JSON)
                .content(subscription("QOS_S", "10.45.0.99")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.invalidParams[0].param").value("/ueIpv4Addr"))
                .andExpect(jsonPath("$.detail").value(containsString("No PDU session has the UE address 10.45.0.99")));

        assertThat(PCF.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(pccRule5qis()).isEmpty();
    }

    private static Document pccRule(int fiveQi, boolean withFlow) {
        List<Document> flows = withFlow
                ? List.of(new Document("direction", 2).append("description", "permit out ip from any to assigned"))
                : List.of();
        return new Document("_id", new ObjectId()).append("flow", flows).append("qos", new Document("index", fiveQi));
    }

    private void givePccRules(Document... rules) {
        mongo.updateFirst(new Query(Criteria.where("imsi").is("001010000000001")),
                new Update().set("slice.0.session.0.pcc_rule", List.of(rules)), "subscribers");
    }

    /** The SMF fixture, with this many QoS flows in the UE's PDU session (the first the default one). */
    private static String withQosFlows(int count) {
        ObjectNode ue = (ObjectNode) JSON.readTree(fixture("ue-session.json"));
        ArrayNode flows = ((ObjectNode) ue.get("pdu").get(0)).putArray("qos_flows");
        for (int qfi = 1; qfi <= count; qfi++) {
            flows.addObject().put("qfi", qfi).put("5qi", qfi == 1 ? 9 : 67);
        }
        return ue.toString();
    }

    @Test
    void aFullSessionFreesThePccRulesNoQosSessionUses() throws Exception {
        // Rules of profiles since removed, a static rule with a flow, and one for the session's default 5QI 9
        givePccRules(pccRule(1, false), pccRule(3, false), pccRule(4, false), pccRule(6, false), pccRule(7, false),
                pccRule(8, false), pccRule(5, true), pccRule(9, false));

        createSession();

        assertThat(pccRule5qis()).containsExactly(5, 9, 67);
        assertThat(takePcfRequest().getPath()).isEqualTo("/npcf-policyauthorization/v1/app-sessions");
    }

    @Test
    void aSessionWhosePccRulesAreAllNeededIsAConflict() throws Exception {
        createSession();
        takePcfRequest();
        // QOS_S's 67 is in use by that session, and the other seven have flows of their own
        givePccRules(pccRule(67, false), pccRule(1, true), pccRule(3, true), pccRule(4, true), pccRule(5, true),
                pccRule(6, true), pccRule(7, true), pccRule(8, true));

        mvc.perform(post(SUBSCRIPTIONS).contentType(MediaType.APPLICATION_JSON).content(subscription("QOS_M")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("already has 8 PCC rules")))
                .andExpect(jsonPath("$.detail").value(containsString("in use by its QoS sessions")));

        assertThat(PCF.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(pccRule5qis()).hasSize(8).contains(67).doesNotContain(2);
    }

    @Test
    void aPduSessionWithFourQosFlowsTakesNoNewAppSession() throws Exception {
        String id = createSession();
        takePcfRequest();
        METRICS.items("/pdu-info", withQosFlows(4));

        mvc.perform(post(SUBSCRIPTIONS).contentType(MediaType.APPLICATION_JSON).content(subscription("QOS_S")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("already has 4 QoS flows")));
        // Another 5QI would take a new app session, and so a fifth flow for a while
        mvc.perform(patch(SUBSCRIPTIONS + "/" + id).contentType(MERGE_PATCH).content("{\"qosReference\":\"QOS_M\"}"))
                .andExpect(status().isConflict());
        assertThat(PCF.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(pccRule5qis()).containsExactly(67);

        // New flows with the same profile change the app session in place, which takes no new flow
        mvc.perform(patch(SUBSCRIPTIONS + "/" + id).contentType(MERGE_PATCH)
                .content("{\"flowInfo\":[{\"flowId\":1,\"flowDescriptions\":[\"permit out ip from any to 10.45.0.7\"]}]}"))
                .andExpect(status().isOk());
        assertThat(takePcfRequest().getMethod()).isEqualTo("PATCH");
        mvc.perform(get(SUBSCRIPTIONS + "/" + id)).andExpect(jsonPath("$.qosReference").value("QOS_S"));
    }

    @Test
    void thePcfEndingASessionNotifiesTheScsAs() throws Exception {
        String id = createSession();
        takePcfRequest();
        scsAs.enqueue(new MockResponse().setResponseCode(204));

        mvc.perform(post("/notifications/as1/" + id + "/terminate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"termCause\":\"PDU_SESSION_TERMINATION\"}"))
                .andExpect(status().isNoContent());

        RecordedRequest notification = scsAs.takeRequest(5, TimeUnit.SECONDS);
        assertThat(notification.getPath()).isEqualTo("/callback/v0/notifications");
        JsonNode body = JSON.readTree(notification.getBody().readUtf8());
        assertThat(body.get("transaction").asString()).isEqualTo(id);
        assertThat(body.at("/eventReports/0/event").asString()).isEqualTo("SESSION_TERMINATION");
        assertThat(mongo.findById(id, Document.class, "qos_sessions")).isNull();
        // The PCF ended it, so nothing is deleted there
        assertThat(PCF.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void aTerminationIsAckedEvenWhenTheScsAsIsDown() throws Exception {
        String id = createSession();
        takePcfRequest();
        scsAs.shutdown();

        mvc.perform(post("/notifications/as1/" + id + "/terminate")).andExpect(status().isNoContent());

        assertThat(mongo.findById(id, Document.class, "qos_sessions")).isNull();
    }

    @Test
    void anUnknownOrInactiveProfileIsRejectedBeforeThePcf() throws Exception {
        // QOS_D is configured DEPRECATED
        for (String qosReference : List.of("QOS_D", "NOPE")) {
            mvc.perform(post(SUBSCRIPTIONS).contentType(MediaType.APPLICATION_JSON).content(subscription(qosReference)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.invalidParams[0].param").value("/qosReference"));
        }
        assertThat(PCF.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }
}
