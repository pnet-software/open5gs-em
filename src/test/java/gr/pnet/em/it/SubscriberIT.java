package gr.pnet.em.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.ResultActions;

/** /subscriber against the Open5GS subscribers collection, with the AMF/SMF metrics stubbed. */
class SubscriberIT extends IntegrationTest {

    private static final String SUBSCRIBERS = "/tep-api/v1/subscriber";

    private static String body(String phoneNumber, String imsi, String k, String dataNetworks) {
        return """
                {"imsi":"%s","phoneNumber":"%s","authenticationManagementField":"8000",
                 "permanentKeyValue":"%s","opcValue":"E8ED289DEBA952E4283B54E88E6183CA","dataNetworks":[%s]}"""
                .formatted(imsi, phoneNumber, k, dataNetworks);
    }

    private static String body(String phoneNumber, String imsi) {
        return body(phoneNumber, imsi, "465B5CE8B199B49FAA5F0A2EE238A6BC",
                "{\"dnn\":\"internet\",\"sst\":\"1\",\"sd\":\"000001\"}");
    }

    private ResultActions create(String body) throws Exception {
        return mvc.perform(post(SUBSCRIBERS).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Creates the subscriber and answers its id, from the Location. */
    private String createdId(String body) throws Exception {
        String location = create(body).andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }

    /** A lookup by the phone number of a device in the body: retrieve, status, location or swaps. */
    private ResultActions lookup(String lookup, String phoneNumber) throws Exception {
        return mvc.perform(post(SUBSCRIBERS + "/" + lookup).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + phoneNumber + "\"}"));
    }

    private Document stored(String imsi) {
        return mongo.findOne(new Query(Criteria.where("imsi").is(imsi)), Document.class, "subscribers");
    }

    @Test
    void createsASubscriberInOpen5gsShapeAtTheIdOfItsDocument() throws Exception {
        create(body("+306900000001", "001010000000001"))
                .andExpect(status().isCreated());

        Document subscriber = stored("001010000000001");
        String id = subscriber.getObjectId("_id").toHexString();
        assertThat(subscriber.getList("msisdn", String.class)).containsExactly("+306900000001");
        assertThat(subscriber.getInteger("schema_version")).isEqualTo(1);
        assertThat(subscriber.get("security", Document.class).getString("k"))
                .isEqualTo("465B5CE8B199B49FAA5F0A2EE238A6BC");
        Document slice = subscriber.getList("slice", Document.class).get(0);
        assertThat(slice.get("sst")).isEqualTo(1);
        assertThat(slice.get("_id")).isInstanceOf(ObjectId.class);
        Document session = slice.getList("session", Document.class).get(0);
        assertThat(session.getString("name")).isEqualTo("internet");
        assertThat(session.getList("pcc_rule", Document.class)).isEmpty();

        mvc.perform(get(SUBSCRIBERS + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.imsi").value("001010000000001"))
                .andExpect(jsonPath("$.dataNetworks[0].sd").value("000001"));
        // Retrieved by the phone number with or without '+'
        for (String phoneNumber : List.of("306900000001", "+306900000001")) {
            lookup("retrieve", phoneNumber)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.imsi").value("001010000000001"));
        }
    }

    @Test
    void createAnswersTheLocationOfTheNewSubscriber() throws Exception {
        String location = create(body("+306900000001", "001010000000001"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");

        assertThat(location).isEqualTo("http://localhost" + SUBSCRIBERS + "/"
                + stored("001010000000001").getObjectId("_id").toHexString());
        mvc.perform(get(location)).andExpect(status().isOk());
    }

    @Test
    void aPhoneNumberIsNotASubscriberId() throws Exception {
        create(body("306900000001", "001010000000001")).andExpect(status().isCreated());

        mvc.perform(get(SUBSCRIBERS + "/306900000001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("Invalid subscriber id")));
        mvc.perform(get(SUBSCRIBERS + "/" + new ObjectId().toHexString())).andExpect(status().isNotFound());
    }

    @Test
    void anUnknownOrInvalidPhoneNumberHasNoSubscriber() throws Exception {
        lookup("retrieve", "306900000009")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No subscriber has phone number 306900000009"));
        lookup("status", "abc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.invalidParams[0].param").value("/phoneNumber"));
    }

    @Test
    void anUpdateKeepsWhatTepDoesNotOwn() throws Exception {
        // As the WebUI wrote it, plus a device the core has seen and a session with its own QoS
        Document webUi = fixtureDocument("subscriber.json");
        webUi.put("imeisv", List.of("3534900612345601"));
        Document firstSession = webUi.getList("slice", Document.class).get(0).getList("session", Document.class).get(0);
        firstSession.get("qos", Document.class).put("index", 7);
        ObjectId id = new ObjectId();
        webUi.put("_id", id);
        mongo.insert(webUi, "subscribers");

        mvc.perform(put(SUBSCRIBERS + "/" + id.toHexString()).contentType(MediaType.APPLICATION_JSON)
                .content(body("+306900000014", "001010000000009", "00000000000000000000000000000000",
                        "{\"dnn\":\"dnn\",\"sst\":\"1\",\"sd\":\"000001\"},"
                                + "{\"dnn\":\"new-dnn\",\"sst\":\"1\",\"sd\":\"000001\"}")))
                .andExpect(status().isNoContent());

        Document updated = stored("001010000000009");
        assertThat(updated.getList("imeisv", String.class)).containsExactly("3534900612345601");
        assertThat(updated.get("security", Document.class))
                .containsEntry("k", "00000000000000000000000000000000")
                .containsKey("op");
        List<Document> slices = updated.getList("slice", Document.class);
        assertThat(slices).hasSize(1);
        List<Document> sessions = slices.get(0).getList("session", Document.class);
        assertThat(sessions).extracting(s -> s.getString("name")).containsExactly("dnn", "new-dnn");
        assertThat(sessions.get(0).get("qos", Document.class).getInteger("index")).isEqualTo(7);
        assertThat(sessions.get(1).get("qos", Document.class).getInteger("index")).isEqualTo(9);
        assertThat(updated.get("ambr")).isEqualTo(webUi.get("ambr"));
    }

    @Test
    void pagesSubscribers() throws Exception {
        create(body("306900000001", "001010000000001")).andExpect(status().isCreated());
        create(body("306900000002", "001010000000002")).andExpect(status().isCreated());
        create(body("306900000003", "001010000000003")).andExpect(status().isCreated());

        mvc.perform(get(SUBSCRIBERS).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(stored("001010000000001").getObjectId("_id").toHexString()))
                .andExpect(jsonPath("$.items[0].phoneNumber").value("306900000001"));
        mvc.perform(get(SUBSCRIBERS).param("limit", "2").param("offset", "2"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].imsi").value("001010000000003"));
    }

    @Test
    void refusesASecondSubscriberWithTheSameImsiOrPhoneNumber() throws Exception {
        create(body("+306900000001", "001010000000001")).andExpect(status().isCreated());

        create(body("306900000001", "001010000000002")).andExpect(status().isConflict());
        create(body("306900000002", "001010000000001")).andExpect(status().isConflict());
    }

    @Test
    void anUpdateCanNotTakeAnotherSubscribersPhoneNumber() throws Exception {
        String id = createdId(body("306900000001", "001010000000001"));
        create(body("306900000002", "001010000000002")).andExpect(status().isCreated());

        mvc.perform(put(SUBSCRIBERS + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("306900000002", "001010000000001")))
                .andExpect(status().isConflict());
    }

    @Test
    void aSubscriberKeepsItsIdThroughASimSwapAndANumberChange() throws Exception {
        String id = createdId(body("306900000001", "001010000000001"));

        mvc.perform(put(SUBSCRIBERS + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("306900000009", "001010000000009")))
                .andExpect(status().isNoContent());

        mvc.perform(get(SUBSCRIBERS + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imsi").value("001010000000009"))
                .andExpect(jsonPath("$.phoneNumber").value("306900000009"));
        lookup("retrieve", "306900000009").andExpect(jsonPath("$.id").value(id));
        lookup("retrieve", "306900000001").andExpect(status().isNotFound());
    }

    @Test
    void deletesASubscriber() throws Exception {
        String id = createdId(body("306900000001", "001010000000001"));

        mvc.perform(delete(SUBSCRIBERS + "/" + id)).andExpect(status().isNoContent());

        mvc.perform(get(SUBSCRIBERS + "/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Subscriber " + id + " not found"));
        mvc.perform(delete(SUBSCRIBERS + "/" + id)).andExpect(status().isNotFound());
        lookup("retrieve", "306900000001").andExpect(status().isNotFound());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void reportsTheRealTimeStatus(CapturedOutput output) throws Exception {
        // The metrics fixtures are for imsi-001010000000001
        create(body("+306999999999", "001010000000001")).andExpect(status().isCreated());
        METRICS.items("/ue-info", fixture("ue-context.json"));
        METRICS.items("/pdu-info", fixture("ue-session.json"));

        lookup("status", "+306999999999")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionState").value("CONNECTED"))
                .andExpect(jsonPath("$.mcc").value("001"))
                .andExpect(jsonPath("$.mnc").value("01"))
                // 00101 is the home PLMN (application-it.yaml)
                .andExpect(jsonPath("$.roaming").value(false))
                .andExpect(jsonPath("$.pduSessions[0].dnn").value("internet"))
                .andExpect(jsonPath("$.pduSessions[0].pduAddress").value("10.45.0.7"))
                // The slice without an SD, which the SMF reports as ffffff
                .andExpect(jsonPath("$.pduSessions[0].sd").doesNotExist())
                .andExpect(jsonPath("$.pduSessions[0].qosFlows[0].fiveQi").value(9));
        // As ECS lines, as deployed: the access log and one line per call to the AMF and SMF
        assertThat(output).contains("\"message\":\"POST /tep-api/v1/subscriber/status 200 ")
                .contains("\"message\":\"AMF GET /ue-info 200 ").contains("\"message\":\"SMF GET /pdu-info 200 ")
                .doesNotContain("failed to append");
    }

    @Test
    void aSubscriberTheCoreDoesNotSeeIsDeregistered() throws Exception {
        create(body("306999999999", "001010000000001")).andExpect(status().isCreated());

        lookup("status", "306999999999")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionState").value("DEREGISTERED"))
                .andExpect(jsonPath("$.mcc").doesNotExist())
                .andExpect(jsonPath("$.roaming").doesNotExist())
                .andExpect(jsonPath("$.pduSessions").doesNotExist());
    }

    @Test
    void unavailableMetricsAreAProblem500() throws Exception {
        create(body("306999999999", "001010000000001")).andExpect(status().isCreated());
        METRICS.fail("/ue-info", 503);

        lookup("status", "306999999999")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value(containsString("AMF metrics unavailable")));
    }

    @Test
    void locatesTheSubscriberInTheCellTheAmfReports() throws Exception {
        create(body("306999999999", "001010000000001")).andExpect(status().isCreated());

        lookup("location", "306999999999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("isn't registered in an NR cell")));

        // The fixture's UE is in NCI 256 of PLMN 00101, which TEP doesn't have yet: the cell id, but no area
        METRICS.items("/ue-info", fixture("ue-context.json"));
        lookup("location", "306999999999")
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"cellId":"00101-000000100","lastUpdated":"2025-10-24T06:50:16.546307Z"}""", JsonCompareMode.STRICT));

        // Once that cell is registered, its coverage area too
        mvc.perform(post("/tep-api/v1/cell").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"cell-001","plmn":{"mcc":"001","mnc":"01"},"nci":"000000100",
                 "center":{"lat":38.2466,"lon":21.7346},"radius":500}"""))
                .andExpect(status().isCreated());
        lookup("location", "+306999999999")
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"cellId":"00101-000000100","center":{"lat":38.2466,"lon":21.7346},"radius":500,
                         "lastUpdated":"2025-10-24T06:50:16.546307Z"}""", JsonCompareMode.STRICT));
    }

    @Test
    void readsTheSwapsOpen5gsSwapChecksRecorded() throws Exception {
        create(body("306900000001", "001010000000001")).andExpect(status().isCreated());

        // Nothing recorded yet: unknown, which isn't the same as never swapped
        lookup("swaps", "306900000001")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                        "Nothing is recorded on the swaps of subscriber 306900000001, so whether it swapped is unknown"));

        // As swap-checks writes it, keyed by the stored MSISDN
        mongo.insert(new Document("_id", "306900000001")
                .append("imsi", "001010000000001")
                .append("imeisv", "3534900612345601")
                .append("lastUpdatedImsi", Date.from(Instant.parse("2026-10-01T12:00:00Z")))
                .append("lastUpdatedImeisv", Date.from(Instant.parse("2026-10-02T08:30:00Z")))
                .append("_class", "gr.pnet.open5gsmongostreams.models.Open5gsSwapCheckDocument"), "swap_events");

        lookup("swaps", "+306900000001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("306900000001"))
                .andExpect(jsonPath("$.imsi").value("001010000000001"))
                .andExpect(jsonPath("$.latestSimChange").value("2026-10-01T12:00:00Z"))
                .andExpect(jsonPath("$.imeisv").value("3534900612345601"))
                .andExpect(jsonPath("$.latestDeviceChange").value("2026-10-02T08:30:00Z"));
    }
}
