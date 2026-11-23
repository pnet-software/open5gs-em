package gr.pnet.em.nef.qossession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import gr.pnet.em.error.InvalidRequestException;
import gr.pnet.em.error.ResourceNotFoundException;
import gr.pnet.em.generated.tep.model.AsSessionWithQoSSubscription;
import gr.pnet.em.generated.tep.model.AsSessionWithQoSSubscriptionPatch;
import gr.pnet.em.generated.tep.model.FlowInfo;
import gr.pnet.em.generated.tep.model.TepQualityProfile;
import gr.pnet.em.generated.tep.model.UserPlaneEvent;
import gr.pnet.em.generated.tep.model.UserPlaneNotificationData;
import gr.pnet.em.nef.notification.ScsAsNotifier;
import gr.pnet.em.nef.pcf.PcfPolicyAuthorizationClient;
import gr.pnet.em.tep.qualityprofile.QualityProfileService;
import gr.pnet.em.tep.subscriber.PccRuleService;

class QosSessionServiceTest {

    private static final String SCS_AS = "as1";
    private static final URI COLLECTION = URI.create("http://em/tep-api/v1/3gpp-as-session-with-qos/v1/as1/subscriptions");
    private static final String PCF_LOCATION = "http://pcf/npcf-policyauthorization/v1/app-sessions/42";

    private final QosSessionRepository repository = mock(QosSessionRepository.class);
    private final QualityProfileService profiles = mock(QualityProfileService.class);
    private final PccRuleService pccRules = mock(PccRuleService.class);
    private final PcfPolicyAuthorizationClient pcf = mock(PcfPolicyAuthorizationClient.class);
    private final ScsAsNotifier notifier = mock(ScsAsNotifier.class);
    private final QosSessionService service = new QosSessionService(repository, profiles, pccRules, pcf, notifier);

    static TepQualityProfile profile(String status) {
        return new TepQualityProfile("QOS_S", TepQualityProfile.StatusEnum.fromValue(status)).fiveQi(67);
    }

    static AsSessionWithQoSSubscription subscription(String qosReference) {
        AsSessionWithQoSSubscription subscription = new AsSessionWithQoSSubscription("http://scs-as/callback");
        subscription.setQosReference(qosReference);
        subscription.setUeIpv4Addr("10.45.0.7");
        return subscription;
    }

    @Test
    void createsTheAppSessionAndStoresTheSession() {
        TepQualityProfile profile = profile("ACTIVE");
        when(profiles.get("QOS_S")).thenReturn(profile);
        when(pcf.createAppSession(eq(SCS_AS), anyString(), any(), eq(profile))).thenReturn(PCF_LOCATION);

        AsSessionWithQoSSubscription created = service.create(SCS_AS, subscription("QOS_S"),
                id -> URI.create(COLLECTION + "/" + id));

        ArgumentCaptor<QosSessionDocument> stored = ArgumentCaptor.forClass(QosSessionDocument.class);
        verify(repository).insert(stored.capture());
        QosSessionDocument session = stored.getValue();
        assertThat(session.appSessionId()).isEqualTo("42");
        assertThat(session.appSessionUri()).isEqualTo(PCF_LOCATION);
        assertThat(session.scsAsId()).isEqualTo(SCS_AS);
        assertThat(session.notificationDestination()).isEqualTo("http://scs-as/callback");
        assertThat(created.getSelf()).isEqualTo(COLLECTION + "/" + session.id());
        assertThat(session.subscription()).isSameAs(created);
        // The UE's PDU session gets the PCC rule for the profile's 5QI before the PCF is asked
        InOrder order = inOrder(pccRules, pcf);
        order.verify(pccRules).ensurePccRule("10.45.0.7", 67, Set.of(), true);
        order.verify(pcf).createAppSession(SCS_AS, session.id(), created, profile);
    }

    @Test
    void keepsThePccRulesOfTheOtherSessionsOnTheUesAddress() {
        when(profiles.get("QOS_S")).thenReturn(profile("ACTIVE"));
        when(profiles.get("QOS_GONE")).thenThrow(new ResourceNotFoundException("Quality profile QOS_GONE not found"));
        when(pcf.createAppSession(anyString(), anyString(), any(), any())).thenReturn(PCF_LOCATION);
        // One with its 5QI recorded, one stored before that (its profile's 5QI), one whose profile is gone
        when(repository.findAllBySubscription_UeIpv4Addr("10.45.0.7")).thenReturn(List.of(
                new QosSessionDocument("s1", SCS_AS, "41", PCF_LOCATION, 2, "http://scs-as", subscription("QOS_M")),
                new QosSessionDocument("s2", "as2", "40", PCF_LOCATION, null, "http://scs-as", subscription("QOS_S")),
                new QosSessionDocument("s3", "as2", "39", PCF_LOCATION, null, "http://scs-as",
                        subscription("QOS_GONE"))));

        service.create(SCS_AS, subscription("QOS_S"), id -> COLLECTION);

        verify(pccRules).ensurePccRule("10.45.0.7", 67, Set.of(2, 67), true);
    }

    @Test
    void needsTheUesIpv4Address() {
        when(profiles.get("QOS_S")).thenReturn(profile("ACTIVE"));
        AsSessionWithQoSSubscription subscription = subscription("QOS_S");
        subscription.setUeIpv4Addr(null);

        assertThatThrownBy(() -> service.create(SCS_AS, subscription, id -> COLLECTION))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        e -> assertThat(e.getParam()).isEqualTo("/ueIpv4Addr"));
        verifyNoInteractions(pccRules, pcf);
    }

    @Test
    void asksNothingOfThePcfWhenThePccRuleCanNotBeGiven() {
        when(profiles.get("QOS_S")).thenReturn(profile("ACTIVE"));
        doThrow(new InvalidRequestException("/ueIpv4Addr", "No PDU session has the UE address 10.45.0.7"))
                .when(pccRules).ensurePccRule("10.45.0.7", 67, Set.of(), true);

        assertThatThrownBy(() -> service.create(SCS_AS, subscription("QOS_S"), id -> COLLECTION))
                .hasMessageContaining("No PDU session");
        verifyNoInteractions(pcf);
    }

    @Test
    void deletesTheAppSessionWhenTheSessionCanNotBeStored() {
        when(profiles.get("QOS_S")).thenReturn(profile("ACTIVE"));
        when(pcf.createAppSession(anyString(), anyString(), any(), any())).thenReturn(PCF_LOCATION);
        when(repository.insert(any(QosSessionDocument.class))).thenThrow(new IllegalStateException("Mongo down"));

        assertThatThrownBy(() -> service.create(SCS_AS, subscription("QOS_S"), id -> COLLECTION))
                .hasMessage("Mongo down");
        verify(pcf).deleteAppSession("42");
    }

    @Test
    void needsAQosReference() {
        assertInvalidQosReference(subscription(null), "required");
    }

    @Test
    void needsAnExistingProfile() {
        when(profiles.get("QOS_X")).thenThrow(new ResourceNotFoundException("Quality profile QOS_X not found"));

        assertInvalidQosReference(subscription("QOS_X"), "No quality profile is named QOS_X");
    }

    @Test
    void needsAnActiveProfile() {
        when(profiles.get("QOS_S")).thenReturn(profile("DEPRECATED"));

        assertInvalidQosReference(subscription("QOS_S"), "DEPRECATED");
    }

    private void assertInvalidQosReference(AsSessionWithQoSSubscription subscription, String reason) {
        assertThatThrownBy(() -> service.create(SCS_AS, subscription, id -> COLLECTION))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> {
                    assertThat(e.getParam()).isEqualTo("/qosReference");
                    assertThat(e.getMessage()).contains(reason);
                });
        verify(pcf, never()).createAppSession(anyString(), anyString(), any(), any());
        verifyNoInteractions(pccRules);
    }

    @Test
    void listsAndGetsTheSessionsOfAnScsAs() {
        QosSessionDocument session = new QosSessionDocument("s1", SCS_AS, "42", PCF_LOCATION, 67, "http://scs-as",
                subscription("QOS_S"));
        when(repository.findAllByScsAsId(SCS_AS)).thenReturn(List.of(session));
        when(repository.findByScsAsIdAndId(SCS_AS, "s1")).thenReturn(Optional.of(session));

        assertThat(service.list(SCS_AS)).containsExactly(session.subscription());
        assertThat(service.get(SCS_AS, "s1")).isSameAs(session.subscription());
    }

    @Test
    void anUnknownSessionIsNotFound() {
        when(repository.findByScsAsIdAndId(SCS_AS, "s1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(SCS_AS, "s1")).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("s1").hasMessageContaining(SCS_AS);
    }

    @Test
    void deleteEndsTheAppSessionAndReportsTheTermination() {
        QosSessionDocument session = new QosSessionDocument("s1", SCS_AS, "42", PCF_LOCATION, 67, "http://scs-as",
                subscription("QOS_S"));
        when(repository.findByScsAsIdAndId(SCS_AS, "s1")).thenReturn(Optional.of(session));

        UserPlaneNotificationData report = service.delete(SCS_AS, "s1");

        verify(pcf).deleteAppSession("42");
        verify(repository).delete(session);
        assertThat(report.getTransaction()).isEqualTo("s1");
        assertThat(report.getEventReports()).singleElement()
                .satisfies(r -> assertThat(r.getEvent()).isEqualTo(UserPlaneEvent.SESSION_TERMINATION));
    }

    @Test
    void aNetworkTerminationNotifiesTheScsAsAndForgetsTheSession() {
        QosSessionDocument session = new QosSessionDocument("s1", SCS_AS, "42", PCF_LOCATION, 67, "http://scs-as",
                subscription("QOS_S"));
        when(repository.findByScsAsIdAndId(SCS_AS, "s1")).thenReturn(Optional.of(session));

        service.terminatedByNetwork(SCS_AS, "s1", PCF_LOCATION);

        verify(notifier).notifyTermination("http://scs-as", "s1");
        verify(repository).delete(session);
        verify(pcf, never()).deleteAppSession(anyString());
    }

    @Test
    void theEndOfAnAppSessionTheSessionMovedOffIsIgnored() {
        when(repository.findByScsAsIdAndId(SCS_AS, "s1")).thenReturn(Optional.of(stored(67)));

        service.terminatedByNetwork(SCS_AS, "s1", "http://pcf/npcf-policyauthorization/v1/app-sessions/41");

        verifyNoInteractions(notifier);
        verify(repository, never()).delete(any(QosSessionDocument.class));
    }

    // --- update

    private static final String NEW_LOCATION = "http://pcf/npcf-policyauthorization/v1/app-sessions/43";

    private QosSessionDocument stored(Integer fiveQi) {
        AsSessionWithQoSSubscription subscription = subscription("QOS_S");
        subscription.setFlowInfo(List.of(new FlowInfo(0).flowDescriptions(List.of("permit out ip from any to 10.45.0.7"))));
        return new QosSessionDocument("s1", SCS_AS, "42", PCF_LOCATION, fiveQi, "http://scs-as/callback",
                subscription);
    }

    private QosSessionDocument saved() {
        ArgumentCaptor<QosSessionDocument> saved = ArgumentCaptor.forClass(QosSessionDocument.class);
        verify(repository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void newFlowsWithTheSame5qiChangeTheAppSessionInPlace() {
        when(repository.findByScsAsIdAndId(SCS_AS, "s1")).thenReturn(Optional.of(stored(67)));
        TepQualityProfile profile = profile("ACTIVE");
        when(profiles.get("QOS_S")).thenReturn(profile);
        List<FlowInfo> flows = List.of(new FlowInfo(1).flowDescriptions(List.of("permit out ip from any to 10.45.0.7 80")));

        AsSessionWithQoSSubscription changed = service.update(SCS_AS, "s1",
                new AsSessionWithQoSSubscriptionPatch().flowInfo(flows));

        assertThat(changed.getFlowInfo()).isEqualTo(flows);
        assertThat(changed.getQosReference()).isEqualTo("QOS_S");
        InOrder order = inOrder(pccRules, pcf);
        order.verify(pccRules).ensurePccRule("10.45.0.7", 67, Set.of(), false);
        order.verify(pcf).updateAppSession("42", changed, profile);
        verify(pcf, never()).createAppSession(anyString(), anyString(), any(), any());
        assertThat(saved().appSessionId()).isEqualTo("42");
    }

    @Test
    void aProfileWithAnother5qiMovesTheSessionToANewAppSession() {
        QosSessionDocument session = stored(67);
        when(repository.findByScsAsIdAndId(SCS_AS, "s1")).thenReturn(Optional.of(session));
        // The session's own 5QI stays in use until its old app session goes
        when(repository.findAllBySubscription_UeIpv4Addr("10.45.0.7")).thenReturn(List.of(session));
        TepQualityProfile other = new TepQualityProfile("QOS_M", TepQualityProfile.StatusEnum.ACTIVE).fiveQi(2);
        when(profiles.get("QOS_M")).thenReturn(other);
        when(pcf.createAppSession(eq(SCS_AS), eq("s1"), any(), eq(other))).thenReturn(NEW_LOCATION);

        AsSessionWithQoSSubscription changed = service.update(SCS_AS, "s1",
                new AsSessionWithQoSSubscriptionPatch().qosReference("QOS_M").notificationDestination("http://scs-as/v2"));

        // The flows stay, the new app session comes first, and the old one goes only once the session is on the new
        assertThat(changed.getFlowInfo()).hasSize(1);
        InOrder order = inOrder(pccRules, pcf, repository);
        order.verify(pccRules).ensurePccRule("10.45.0.7", 2, Set.of(67), true);
        order.verify(pcf).createAppSession(SCS_AS, "s1", changed, other);
        order.verify(repository).save(any(QosSessionDocument.class));
        order.verify(pcf).deleteAppSession("42");
        verify(pcf, never()).updateAppSession(anyString(), any(), any());
        QosSessionDocument moved = saved();
        assertThat(moved.appSessionId()).isEqualTo("43");
        assertThat(moved.appSessionUri()).isEqualTo(NEW_LOCATION);
        assertThat(moved.fiveQi()).isEqualTo(2);
        assertThat(moved.notificationDestination()).isEqualTo("http://scs-as/v2");
        assertThat(moved.subscription().getQosReference()).isEqualTo("QOS_M");
    }

    @Test
    void aSessionWithoutARecorded5qiMovesToANewAppSession() {
        when(repository.findByScsAsIdAndId(SCS_AS, "s1")).thenReturn(Optional.of(stored(null)));
        when(profiles.get("QOS_S")).thenReturn(profile("ACTIVE"));
        when(pcf.createAppSession(anyString(), anyString(), any(), any())).thenReturn(NEW_LOCATION);

        service.update(SCS_AS, "s1", new AsSessionWithQoSSubscriptionPatch().qosReference("QOS_S"));

        verify(pcf).deleteAppSession("42");
        assertThat(saved().fiveQi()).isEqualTo(67);
    }

    @Test
    void anOldAppSessionThatCanNotBeDeletedLeavesTheSessionMoved() {
        when(repository.findByScsAsIdAndId(SCS_AS, "s1")).thenReturn(Optional.of(stored(9)));
        when(profiles.get("QOS_S")).thenReturn(profile("ACTIVE"));
        when(pcf.createAppSession(anyString(), anyString(), any(), any())).thenReturn(NEW_LOCATION);
        doThrow(new IllegalStateException("PCF down")).when(pcf).deleteAppSession("42");

        AsSessionWithQoSSubscription changed = service.update(SCS_AS, "s1",
                new AsSessionWithQoSSubscriptionPatch().qosReference("QOS_S"));

        assertThat(changed.getQosReference()).isEqualTo("QOS_S");
        assertThat(saved().appSessionId()).isEqualTo("43");
    }

    @Test
    void aNewAppSessionThatCanNotBeStoredIsDeleted() {
        when(repository.findByScsAsIdAndId(SCS_AS, "s1")).thenReturn(Optional.of(stored(9)));
        when(profiles.get("QOS_S")).thenReturn(profile("ACTIVE"));
        when(pcf.createAppSession(anyString(), anyString(), any(), any())).thenReturn(NEW_LOCATION);
        when(repository.save(any(QosSessionDocument.class))).thenThrow(new IllegalStateException("Mongo down"));

        assertThatThrownBy(() -> service.update(SCS_AS, "s1",
                new AsSessionWithQoSSubscriptionPatch().qosReference("QOS_S")))
                .hasMessage("Mongo down");
        verify(pcf).deleteAppSession("43");
        verify(pcf, never()).deleteAppSession("42");
    }

    @Test
    void anEmptyPatchChangesNothing() {
        QosSessionDocument session = stored(67);
        when(repository.findByScsAsIdAndId(SCS_AS, "s1")).thenReturn(Optional.of(session));

        assertThat(service.update(SCS_AS, "s1", new AsSessionWithQoSSubscriptionPatch()))
                .isSameAs(session.subscription());
        verifyNoInteractions(pccRules, pcf);
        verify(repository, never()).save(any(QosSessionDocument.class));
    }

    @Test
    void aFieldTepCanNotApplyIsAnInvalidParam() {
        AsSessionWithQoSSubscriptionPatch patch = new AsSessionWithQoSSubscriptionPatch().qosReference("QOS_S")
                .events(List.of(UserPlaneEvent.SESSION_TERMINATION));

        assertThatThrownBy(() -> service.update(SCS_AS, "s1", patch))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> {
                    assertThat(e.getParam()).isEqualTo("/events");
                    assertThat(e.getMessage()).contains("can't be changed");
                });
        verifyNoInteractions(repository, pccRules, pcf);
    }

    @Test
    void aNewProfileMustBeActive() {
        when(repository.findByScsAsIdAndId(SCS_AS, "s1")).thenReturn(Optional.of(stored(67)));
        when(profiles.get("QOS_S")).thenReturn(profile("DEPRECATED"));

        assertThatThrownBy(() -> service.update(SCS_AS, "s1",
                new AsSessionWithQoSSubscriptionPatch().qosReference("QOS_S")))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        e -> assertThat(e.getMessage()).contains("DEPRECATED"));
        verifyNoInteractions(pccRules, pcf);
    }

    @Test
    void newFlowsKeepADeprecatedProfileButNotARemovedOne() {
        when(repository.findByScsAsIdAndId(SCS_AS, "s1")).thenReturn(Optional.of(stored(67)));
        AsSessionWithQoSSubscriptionPatch newFlows = new AsSessionWithQoSSubscriptionPatch().flowInfo(List.of());

        when(profiles.get("QOS_S")).thenReturn(profile("DEPRECATED"));
        service.update(SCS_AS, "s1", newFlows);
        verify(pcf).updateAppSession(eq("42"), any(), any());

        when(profiles.get("QOS_S")).thenThrow(new ResourceNotFoundException("Quality profile QOS_S not found"));
        assertThatThrownBy(() -> service.update(SCS_AS, "s1", newFlows))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> {
                    assertThat(e.getParam()).isEqualTo("/qosReference");
                    assertThat(e.getMessage()).contains("no longer configured");
                });
    }
}
