package gr.pnet.em.tep.subscriber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import gr.pnet.em.error.InvalidRequestException;
import gr.pnet.em.generated.tep.model.TepSubscriber;
import gr.pnet.em.generated.tep.model.TepSubscriberDataNetwork;

class Open5gsSubscriberMapperTest {

    /** A subscriber as the Open5GS WebUI stores it: two slices, three sessions. */
    static Document fixture() {
        try (InputStream in = Open5gsSubscriberMapperTest.class.getResourceAsStream("/open5gs/subscriber.json")) {
            return Document.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static TepSubscriber tepSubscriber(String phoneNumber, String imsi, TepSubscriberDataNetwork... dataNetworks) {
        return new TepSubscriber(imsi, phoneNumber, "8000", "465B5CE8B199B49FAA5F0A2EE238A6BC",
                "E8ED289DEBA952E4283B54E88E6183CA", List.of(dataNetworks));
    }

    @Test
    void mapsAnOpen5gsSubscriberToTep() {
        TepSubscriber tep = Open5gsSubscriberMapper.toTep(fixture());

        assertThat(tep.getImsi()).isEqualTo("001010000000009");
        assertThat(tep.getPhoneNumber()).isEqualTo("+306900000014");
        assertThat(tep.getAuthenticationManagementField()).isEqualTo("8000");
        assertThat(tep.getPermanentKeyValue()).isEqualTo("465B5CE8B199B49FAA5F0A2EE238A6BC");
        assertThat(tep.getOpcValue()).isEqualTo("E8ED289DEBA952E4283B54E88E6183CA");
        assertThat(tep.getDataNetworks()).containsExactly(
                new TepSubscriberDataNetwork("dnn", "1").sd("000001"),
                new TepSubscriberDataNetwork("dnn-same-slice", "1").sd("000001"),
                new TepSubscriberDataNetwork("dnn-different-slice", "2").sd("000002"));
    }

    @Test
    void mapsASubscriberWithoutSecurityOrSlices() {
        TepSubscriber tep = Open5gsSubscriberMapper.toTep(new Document("imsi", "001010000000001"));

        assertThat(tep.getImsi()).isEqualTo("001010000000001");
        assertThat(tep.getPhoneNumber()).isNull();
        assertThat(tep.getPermanentKeyValue()).isNull();
        assertThat(tep.getDataNetworks()).isEmpty();
    }

    @Test
    void mapsARef() {
        assertThat(Open5gsSubscriberMapper.toRef(fixture()).getPhoneNumber()).isEqualTo("+306900000014");
        assertThat(Open5gsSubscriberMapper.toRef(fixture()).getImsi()).isEqualTo("001010000000009");
    }

    @Test
    void theIdIsTheDocumentsId() {
        ObjectId id = new ObjectId("6731f0c2a4b5c6d7e8f90123");
        Document stored = fixture().append("_id", id);

        assertThat(Open5gsSubscriberMapper.toTep(stored).getId()).isEqualTo("6731f0c2a4b5c6d7e8f90123");
        assertThat(Open5gsSubscriberMapper.toRef(stored).getId()).isEqualTo("6731f0c2a4b5c6d7e8f90123");
        assertThat(Open5gsSubscriberMapper.toTep(new Document("imsi", "001010000000001")).getId()).isNull();
    }

    @Test
    void newSubscriberGetsTheOpen5gsDefaults() {
        Document subscriber = Open5gsSubscriberMapper.newSubscriber(tepSubscriber("306900000001", "001010000000001",
                new TepSubscriberDataNetwork("internet", "1").sd("000001"), new TepSubscriberDataNetwork("ims", "1").sd("000001"),
                new TepSubscriberDataNetwork("edge", "2")));

        assertThat(subscriber.get("_id")).isInstanceOf(ObjectId.class);
        assertThat(subscriber.getString("imsi")).isEqualTo("001010000000001");
        assertThat(subscriber.getList("msisdn", String.class)).containsExactly("306900000001");
        assertThat(subscriber.getList("imeisv", String.class)).isEmpty();
        assertThat(subscriber.get("security", Document.class))
                .containsEntry("k", "465B5CE8B199B49FAA5F0A2EE238A6BC")
                .containsEntry("amf", "8000")
                .containsEntry("opc", "E8ED289DEBA952E4283B54E88E6183CA")
                .containsEntry("op", null);
        assertThat(subscriber.getInteger("access_restriction_data")).isEqualTo(32);
        assertThat(subscriber.getInteger("subscribed_rau_tau_timer")).isEqualTo(12);

        List<Document> slices = Open5gsSubscriberMapper.documents(subscriber, "slice");
        assertThat(slices).hasSize(2);
        assertThat(slices.get(0)).containsEntry("sst", 1).containsEntry("sd", "000001")
                .containsEntry("default_indicator", true);
        assertThat(slices.get(1)).containsEntry("sst", 2).containsEntry("default_indicator", false)
                .doesNotContainKey("sd");
        assertThat(slices.get(0).get("_id")).isInstanceOf(ObjectId.class);

        List<Document> sessions = Open5gsSubscriberMapper.documents(slices.get(0), "session");
        assertThat(sessions).extracting(s -> s.getString("name")).containsExactly("internet", "ims");
        Document session = sessions.get(0);
        assertThat(session.getInteger("type")).isEqualTo(3);
        assertThat(session.get("qos", Document.class).getInteger("index")).isEqualTo(9);
        assertThat(session.get("qos", Document.class).get("arp", Document.class))
                .containsEntry("priority_level", 8);
        // An AS session adds the PCC rule it needs
        assertThat(Open5gsSubscriberMapper.documents(session, "pcc_rule")).isEmpty();
    }

    @Test
    void aPccRuleIsForItsFiveQiWithOpenRates() {
        Document rule = Open5gsSubscriberMapper.pccRule(67);

        assertThat(rule.get("_id")).isInstanceOf(ObjectId.class);
        Document qos = rule.get("qos", Document.class);
        assertThat(qos.getInteger("index")).isEqualTo(67);
        assertThat(qos.get("arp", Document.class)).containsEntry("priority_level", 2);
        // An unlimited MBR has no value
        assertThat(qos.get("mbr", Document.class).get("downlink", Document.class))
                .doesNotContainKey("value").containsEntry("unit", 1);
        assertThat(qos.get("gbr", Document.class).get("uplink", Document.class))
                .containsEntry("value", 1).containsEntry("unit", 1);
    }

    @Test
    void aSliceWithoutAnSdIsTheOneTheCoreReportsWithSdFfffff() {
        Document noSd = new Document("sst", 1);
        Document sd = new Document("sst", 1).append("sd", "000001");

        assertThat(Open5gsSubscriberMapper.isReportedSlice(noSd, 1, "ffffff")).isTrue();
        assertThat(Open5gsSubscriberMapper.isReportedSlice(noSd, 1, null)).isTrue();
        assertThat(Open5gsSubscriberMapper.isReportedSlice(noSd, 2, "ffffff")).isFalse();
        assertThat(Open5gsSubscriberMapper.isReportedSlice(sd, 1, "000001")).isTrue();
        assertThat(Open5gsSubscriberMapper.isReportedSlice(sd, 1, "ffffff")).isFalse();
    }

    @Test
    void tellsWhetherASessionHasAPccRuleForAFiveQi() {
        Document session = new Document("pcc_rule", List.of(Open5gsSubscriberMapper.pccRule(2),
                new Document("qos", new Document("index", 67L))));

        assertThat(Open5gsSubscriberMapper.hasPccRule(session, 2)).isTrue();
        assertThat(Open5gsSubscriberMapper.hasPccRule(session, 67)).isTrue();
        assertThat(Open5gsSubscriberMapper.hasPccRule(session, 5)).isFalse();
        assertThat(Open5gsSubscriberMapper.hasPccRule(new Document(), 5)).isFalse();
    }

    @Test
    void mergeKeepsExistingSlicesAndSessionsAsTheyAre() {
        List<Document> existing = Open5gsSubscriberMapper.documents(fixture(), "slice");
        Document existingSession = Open5gsSubscriberMapper.documents(existing.get(0), "session").get(0);
        existingSession.get("qos", Document.class).put("index", 7);

        List<Document> merged = Open5gsSubscriberMapper.mergeSlices(existing, List.of(
                new TepSubscriberDataNetwork("dnn", "1").sd("000001"),
                new TepSubscriberDataNetwork("new-dnn", "1").sd("000001")));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).getBoolean("default_indicator")).isTrue();
        List<Document> sessions = Open5gsSubscriberMapper.documents(merged.get(0), "session");
        assertThat(sessions).extracting(s -> s.getString("name")).containsExactly("dnn", "new-dnn");
        // The kept session keeps its own QoS; the new one gets the default
        assertThat(sessions.get(0)).isSameAs(existingSession);
        assertThat(sessions.get(1).get("qos", Document.class).getInteger("index")).isEqualTo(9);
        // The existing slice was copied, not changed
        assertThat(Open5gsSubscriberMapper.documents(existing.get(0), "session")).hasSize(2);
    }

    @Test
    void mergeMakesTheFirstSliceDefaultWhenTheDefaultOneWasDropped() {
        List<Document> existing = Open5gsSubscriberMapper.documents(fixture(), "slice");

        List<Document> merged = Open5gsSubscriberMapper.mergeSlices(existing,
                List.of(new TepSubscriberDataNetwork("dnn-different-slice", "2").sd("000002")));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0)).containsEntry("sst", 2).containsEntry("default_indicator", true);
    }

    @Test
    void mergeIgnoresARepeatedDataNetwork() {
        List<Document> merged = Open5gsSubscriberMapper.mergeSlices(List.of(),
                List.of(new TepSubscriberDataNetwork("internet", "1"), new TepSubscriberDataNetwork("internet", "1")));

        assertThat(Open5gsSubscriberMapper.documents(merged.get(0), "session")).hasSize(1);
    }

    @Test
    void mergeOfNoDataNetworksHasNoSlices() {
        assertThat(Open5gsSubscriberMapper.mergeSlices(List.of(), null)).isEmpty();
    }

    @Test
    void rejectsAnSstThatIsNotANumberFrom0To255() {
        assertThatThrownBy(() -> Open5gsSubscriberMapper.mergeSlices(List.of(),
                List.of(new TepSubscriberDataNetwork("internet", "eMBB"))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("eMBB");
        assertThatThrownBy(() -> Open5gsSubscriberMapper.mergeSlices(List.of(),
                List.of(new TepSubscriberDataNetwork("internet", "256"))))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void replacesOnlyTheFirstMsisdn() {
        Document subscriber = new Document("msisdn", List.of("306900000001", "306900000002"));

        assertThat(Open5gsSubscriberMapper.replaceFirstMsisdn(subscriber, "306900000003"))
                .containsExactly("306900000003", "306900000002");
        assertThat(Open5gsSubscriberMapper.replaceFirstMsisdn(subscriber, "306900000002"))
                .containsExactly("306900000002");
        assertThat(Open5gsSubscriberMapper.replaceFirstMsisdn(new Document(), "306900000003"))
                .containsExactly("306900000003");
    }
}
