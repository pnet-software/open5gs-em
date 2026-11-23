package gr.pnet.em.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import gr.pnet.em.error.InvalidRequestException;
import gr.pnet.em.error.ResourceNotFoundException;
import gr.pnet.em.generated.tep.AsSessionWithRequiredQoSSubscriptionsApiController;
import gr.pnet.em.generated.tep.IndividualAsSessionWithRequiredQoSSubscriptionApiController;
import gr.pnet.em.generated.tep.model.AsSessionWithQoSSubscription;
import gr.pnet.em.generated.tep.model.UserPlaneEvent;
import gr.pnet.em.generated.tep.model.UserPlaneEventReport;
import gr.pnet.em.generated.tep.model.UserPlaneNotificationData;
import gr.pnet.em.nef.notification.PcfNotificationController;
import gr.pnet.em.nef.qossession.AsSessionWithQoSDelegate;
import gr.pnet.em.nef.qossession.IndividualAsSessionWithQoSDelegate;
import gr.pnet.em.nef.qossession.QosSessionService;

/** The 3GPP AsSessionWithQoS routes under /tep-api/v1, and the PCF's notification callback. */
@WebMvcTest({ AsSessionWithRequiredQoSSubscriptionsApiController.class,
        IndividualAsSessionWithRequiredQoSSubscriptionApiController.class, PcfNotificationController.class })
@Import({ AsSessionWithQoSDelegate.class, IndividualAsSessionWithQoSDelegate.class })
class AsSessionWithQoSWebTest {

    private static final String SUBSCRIPTIONS = "/tep-api/v1/3gpp-as-session-with-qos/v1/as1/subscriptions";
    private static final String BODY = """
            {"supportedFeatures":"12","notificationDestination":"http://scs-as:30101/callback/v0",
             "flowInfo":[{"flowId":0,"flowDescriptions":["permit in ip from 10.45.0.13 any to 0.0.0.0/0 any",
               "permit out ip from 0.0.0.0/0 any to 10.45.0.13 any"]}],
             "qosReference":"QOS_S","ueIpv4Addr":"10.45.0.13","ethFlowInfo":null}""";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private QosSessionService sessions;

    @Test
    void createsASessionUnderTheTepBasePath() throws Exception {
        when(sessions.create(eq("as1"), any(), any())).thenAnswer(invocation -> {
            AsSessionWithQoSSubscription subscription = invocation.getArgument(1);
            Function<String, URI> selfLink = invocation.getArgument(2);
            subscription.setSelf(selfLink.apply("s1").toString());
            return subscription;
        });

        mvc.perform(post(SUBSCRIPTIONS).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost" + SUBSCRIPTIONS + "/s1"))
                .andExpect(jsonPath("$.self").value("http://localhost" + SUBSCRIPTIONS + "/s1"))
                .andExpect(jsonPath("$.qosReference").value("QOS_S"))
                // Lists the SCS/AS didn't send stay out of the answer
                .andExpect(jsonPath("$.ethFlowInfo").doesNotExist())
                .andExpect(jsonPath("$.events").doesNotExist());
        verify(sessions).create(eq("as1"), argThat(s -> "10.45.0.13".equals(s.getUeIpv4Addr())
                && s.getFlowInfo().get(0).getFlowDescriptions().size() == 2), any());
    }

    @Test
    void aSessionNeedsANotificationDestination() throws Exception {
        mvc.perform(post(SUBSCRIPTIONS).contentType(MediaType.APPLICATION_JSON).content("{\"qosReference\":\"QOS_S\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"));
        verifyNoInteractions(sessions);
    }

    @Test
    void anUnusableQosReferenceIsAnInvalidParam() throws Exception {
        when(sessions.create(eq("as1"), any(), any()))
                .thenThrow(new InvalidRequestException("/qosReference", "No quality profile is named QOS_S"));

        mvc.perform(post(SUBSCRIPTIONS).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.invalidParams[0].param").value("/qosReference"))
                .andExpect(jsonPath("$.detail").value("No quality profile is named QOS_S"));
    }

    @Test
    void listsGetsAndDeletesSessions() throws Exception {
        AsSessionWithQoSSubscription subscription = new AsSessionWithQoSSubscription("http://scs-as/callback");
        subscription.setSelf("http://localhost" + SUBSCRIPTIONS + "/s1");
        when(sessions.list("as1")).thenReturn(List.of(subscription));
        when(sessions.get("as1", "s1")).thenReturn(subscription);
        when(sessions.delete("as1", "s1")).thenReturn(new UserPlaneNotificationData().transaction("s1")
                .eventReports(List.of(new UserPlaneEventReport().event(UserPlaneEvent.SESSION_TERMINATION))));

        mvc.perform(get(SUBSCRIPTIONS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].self").value("http://localhost" + SUBSCRIPTIONS + "/s1"));
        mvc.perform(get(SUBSCRIPTIONS + "/s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationDestination").value("http://scs-as/callback"));
        mvc.perform(delete(SUBSCRIPTIONS + "/s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction").value("s1"))
                .andExpect(jsonPath("$.eventReports[0].event").value("SESSION_TERMINATION"));
    }

    @Test
    void anUnknownSessionIsAProblem404() throws Exception {
        when(sessions.get("as1", "s9")).thenThrow(new ResourceNotFoundException("QoS session s9 of SCS/AS as1 not found"));

        mvc.perform(get(SUBSCRIPTIONS + "/s9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("QoS session s9 of SCS/AS as1 not found"));
    }

    @Test
    void echoesTheCorrelatorAlthoughThe3gppSpecDoesNotDeclareIt() throws Exception {
        when(sessions.get("as1", "s9")).thenThrow(new ResourceNotFoundException("QoS session s9 of SCS/AS as1 not found"));

        mvc.perform(get(SUBSCRIPTIONS + "/s9").header("x-correlator", "corr-3gpp"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("x-correlator", "corr-3gpp"));
    }

    @Test
    void replacingASessionIsNotImplemented() throws Exception {
        mvc.perform(put(SUBSCRIPTIONS + "/s1").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotImplemented())
                .andExpect(content().contentType("application/problem+json"));
        verifyNoInteractions(sessions);
    }

    @Test
    void changesASessionWithAMergePatch() throws Exception {
        AsSessionWithQoSSubscription changed = new AsSessionWithQoSSubscription("http://scs-as/callback");
        changed.setQosReference("QOS_M");
        when(sessions.update(eq("as1"), eq("s1"), any())).thenReturn(changed);

        mvc.perform(patch(SUBSCRIPTIONS + "/s1").contentType("application/merge-patch+json")
                .content("{\"qosReference\":\"QOS_M\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qosReference").value("QOS_M"));
        verify(sessions).update(eq("as1"), eq("s1"), argThat(p -> "QOS_M".equals(p.getQosReference())
                && p.getFlowInfo() == null));
    }

    @Test
    void aFieldThatCanNotChangeIsAnInvalidParam() throws Exception {
        when(sessions.update(eq("as1"), eq("s1"), any())).thenThrow(new InvalidRequestException("/events",
                "events can't be changed: only qosReference, flowInfo and notificationDestination can"));

        mvc.perform(patch(SUBSCRIPTIONS + "/s1").contentType("application/merge-patch+json")
                .content("{\"events\":[\"SESSION_TERMINATION\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.invalidParams[0].param").value("/events"));
    }

    @Test
    void thePcfReportsATermination() throws Exception {
        mvc.perform(post("/notifications/as1/s1/terminate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"resUri\":\"http://pcf/npcf-policyauthorization/v1/app-sessions/42\","
                        + "\"termCause\":\"PDU_SESSION_TERMINATION\"}"))
                .andExpect(status().isNoContent());
        verify(sessions).terminatedByNetwork("as1", "s1", "http://pcf/npcf-policyauthorization/v1/app-sessions/42");
    }

    @Test
    void aTerminationMayComeWithoutABody() throws Exception {
        mvc.perform(post("/notifications/as1/s1/terminate")).andExpect(status().isNoContent());
        verify(sessions).terminatedByNetwork("as1", "s1", null);
    }
}
