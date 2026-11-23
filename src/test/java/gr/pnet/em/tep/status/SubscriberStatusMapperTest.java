package gr.pnet.em.tep.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import gr.pnet.em.generated.tep.model.TepSubscriberStatus;
import gr.pnet.em.generated.tep.model.TepSubscriberStatus.ConnectionStateEnum;
import gr.pnet.em.generated.tep.model.TepSubscriberStatusPduSession;
import gr.pnet.em.generated.tep.model.TepSubscriberStatusQosFlow;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class SubscriberStatusMapperTest {

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** An Open5GS fixture (an AMF ue-info or SMF pdu-info item), as the record the client reads it into. */
    static <T> T fixture(String name, Class<T> type) {
        try (InputStream in = SubscriberStatusMapperTest.class.getResourceAsStream("/open5gs/" + name)) {
            return JSON.readValue(in, type);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Set<String> HOME = Set.of("00101", "310410");

    private static TepSubscriberStatus toTep(UeSessions.UeSession session, UeContexts.UeContext context) {
        return SubscriberStatusMapper.toTep(session, context, HOME);
    }

    private static UeContexts.UeContext context(String mmState, String cmState, String plmn) {
        return new UeContexts.UeContext("imsi-310410000000001", mmState, cmState,
                new UeContexts.Location(null, new UeContexts.NrTai(plmn, null, null), null, null));
    }

    @Test
    void combinesTheAmfContextAndTheSmfSessions() {
        TepSubscriberStatus status = toTep(
                fixture("ue-session.json", UeSessions.UeSession.class),
                fixture("ue-context.json", UeContexts.UeContext.class));

        assertThat(status.getConnectionState()).isEqualTo(ConnectionStateEnum.CONNECTED);
        assertThat(status.getMcc()).isEqualTo("001");
        assertThat(status.getMnc()).isEqualTo("01");
        assertThat(status.getRoaming()).isFalse();
        // The SMF reports the slice without an SD as sd ffffff
        assertThat(status.getPduSessions()).containsExactly(new TepSubscriberStatusPduSession()
                .pduSessionId("1")
                .dnn("internet")
                .sst("1")
                .pduAddress("10.45.0.7")
                .qosFlows(List.of(new TepSubscriberStatusQosFlow(1, 9))));
    }

    @Test
    void readsTheWholeAmfLocation() {
        UeContexts.Location location = fixture("ue-context.json", UeContexts.UeContext.class).location();

        assertThat(location).isEqualTo(new UeContexts.Location(1761288616546307L,
                new UeContexts.NrTai("00101", "000064", 100),
                new UeContexts.NrCgi("00101", 256L, 16L, 0),
                "000000"));
    }

    @Test
    void aRegisteredUeWithoutSessionsIsConnectedOrIdle() {
        TepSubscriberStatus status = toTep(null,
                fixture("ue-context.json", UeContexts.UeContext.class));

        assertThat(status.getConnectionState()).isEqualTo(ConnectionStateEnum.CONNECTED);
        assertThat(status.getPduSessions()).isNull();
        assertThat(toTep(null, context("registered", "idle", "310410")).getConnectionState())
                .isEqualTo(ConnectionStateEnum.IDLE);
    }

    @Test
    void aUeTheAmfHasNoContextForOrReportsDeregisteredIsDeregistered() {
        assertThat(toTep(null, null))
                .isEqualTo(new TepSubscriberStatus(ConnectionStateEnum.DEREGISTERED));
        assertThat(toTep(null, context("deregistered", "idle", "310410")).getConnectionState())
                .isEqualTo(ConnectionStateEnum.DEREGISTERED);
    }

    @Test
    void sessionsWithoutAnAmfContextHaveNoServingNetwork() {
        TepSubscriberStatus status = toTep(
                fixture("ue-session.json", UeSessions.UeSession.class), null);

        assertThat(status.getConnectionState()).isEqualTo(ConnectionStateEnum.DEREGISTERED);
        assertThat(status.getMcc()).isNull();
        assertThat(status.getRoaming()).isNull();
        assertThat(status.getPduSessions()).hasSize(1);
    }

    @Test
    void readsAThreeDigitMncAndSessionsWithLittleToSay() {
        UeSessions.UeSession session = new UeSessions.UeSession("imsi-310410000000001", List.of(
                new UeSessions.Pdu(null, "internet", null, null, null),
                new UeSessions.Pdu(5, "ims", null, new UeSessions.Snssai(1, "000001"), List.of())));

        TepSubscriberStatus status = toTep(session, context(null, "connected", "310410"));

        assertThat(status.getMcc()).isEqualTo("310");
        assertThat(status.getMnc()).isEqualTo("410");
        assertThat(status.getPduSessions().get(0)).isEqualTo(new TepSubscriberStatusPduSession().dnn("internet"));
        assertThat(status.getPduSessions().get(1)).isEqualTo(new TepSubscriberStatusPduSession()
                .pduSessionId("5").dnn("ims").sst("1").sd("000001").qosFlows(List.of()));
    }

    @Test
    void anUnusablePlmnGivesNoMccOrMnc() {
        TepSubscriberStatus status = toTep(null, context("registered", "idle", "00"));

        assertThat(status.getMcc()).isNull();
        assertThat(status.getMnc()).isNull();
        assertThat(status.getRoaming()).isNull();
    }

    @Test
    void aUeServedByAPlmnThatIsNotAHomePlmnIsRoaming() {
        assertThat(toTep(null, context("registered", "idle", "99950")).getRoaming()).isTrue();
        // The MNC at its own length: 31041 isn't 310410
        assertThat(toTep(null, context("registered", "idle", "31041")).getRoaming()).isTrue();
        assertThat(toTep(null, context("registered", "idle", "310410")).getRoaming()).isFalse();
    }

    @Test
    void withoutHomePlmnsWhetherTheUeIsRoamingCanNotBeTold() {
        TepSubscriberStatus status = SubscriberStatusMapper.toTep(null, context("registered", "idle", "99950"),
                Set.of());

        assertThat(status.getMcc()).isEqualTo("999");
        assertThat(status.getRoaming()).isNull();
    }
}
