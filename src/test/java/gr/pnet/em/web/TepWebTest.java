package gr.pnet.em.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import gr.pnet.em.error.InvalidRequestException;
import gr.pnet.em.error.ResourceConflictException;
import gr.pnet.em.error.ResourceNotFoundException;
import gr.pnet.em.error.UpstreamException;
import gr.pnet.em.generated.tep.CellsApiController;
import gr.pnet.em.generated.tep.QualityProfilesApiController;
import gr.pnet.em.generated.tep.SubscribersApiController;
import gr.pnet.em.generated.tep.model.TepCell;
import gr.pnet.em.generated.tep.model.TepGeoPoint;
import gr.pnet.em.generated.tep.model.TepPlmnId;
import gr.pnet.em.generated.tep.model.TepQualityProfile;
import gr.pnet.em.generated.tep.model.TepSubscriber;
import gr.pnet.em.generated.tep.model.TepSubscriberDataNetwork;
import gr.pnet.em.generated.tep.model.TepSubscriberLocation;
import gr.pnet.em.generated.tep.model.TepSubscriberRef;
import gr.pnet.em.generated.tep.model.TepSubscriberStatus;
import gr.pnet.em.generated.tep.model.TepSubscriberStatus.ConnectionStateEnum;
import gr.pnet.em.generated.tep.model.TepSubscriberStatusPduSession;
import gr.pnet.em.generated.tep.model.TepSwaps;
import gr.pnet.em.support.Slice;
import gr.pnet.em.tep.cell.CellDelegate;
import gr.pnet.em.tep.cell.CellService;
import gr.pnet.em.tep.qualityprofile.QualityProfileDelegate;
import gr.pnet.em.tep.qualityprofile.QualityProfileService;
import gr.pnet.em.tep.status.SubscriberStatusService;
import gr.pnet.em.tep.subscriber.SubscriberDelegate;
import gr.pnet.em.tep.subscriber.SubscriberService;
import gr.pnet.em.tep.swapevent.SwapEventService;

/** The TEP routes through the generated controllers and the real delegates; the services are mocked. */
@WebMvcTest({ SubscribersApiController.class, QualityProfilesApiController.class, CellsApiController.class })
@Import({ SubscriberDelegate.class, QualityProfileDelegate.class, CellDelegate.class })
class TepWebTest {

    private static final String BASE = "/tep-api/v1";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String SUBSCRIBER_BODY = """
            {"imsi":"001010000000001","phoneNumber":"+306900000001","authenticationManagementField":"8000",
             "permanentKeyValue":"465B5CE8B199B49FAA5F0A2EE238A6BC","opcValue":"E8ED289DEBA952E4283B54E88E6183CA",
             "dataNetworks":[{"dnn":"internet","sst":"1","sd":"000001"}]}""";
    private static final String ID = "6731f0c2a4b5c6d7e8f90123";
    private static final String OTHER_ID = "6731f0c2a4b5c6d7e8f90999";
    private static final String DEVICE_BODY = "{\"phoneNumber\":\"+306900000001\"}";
    private static final String PROFILE_BODY = """
            {"name":"QOS_S","status":"ACTIVE","maxDownstreamRate":{"value":5,"unit":"Mbps"},
             "preemptionCapability":"MAY_PREEMPT"}""";
    private static final String CELL_CENTER = "{\"lat\":37.9838,\"lon\":23.7275}";
    private static final String CELL_BODY = """
            {"name":"cell-001","plmn":{"mcc":"999","mnc":"50"},"nci":"000000210","center":%s,"radius":500}"""
            .formatted(CELL_CENTER);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SubscriberService subscribers;
    @MockitoBean
    private SubscriberStatusService statuses;
    @MockitoBean
    private SwapEventService swapEvents;
    @MockitoBean
    private QualityProfileService profiles;
    @MockitoBean
    private CellService cells;

    private static TepSubscriber subscriber() {
        return new TepSubscriber("001010000000001", "+306900000001", "8000", "K", "OPC",
                List.of(new TepSubscriberDataNetwork("internet", "1"))).id(ID);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // --- Subscribers

    @Test
    void listsAPageOfSubscribers() throws Exception {
        when(subscribers.list(50, 0)).thenReturn(new Slice<>(
                List.of(new TepSubscriberRef(ID, "001010000000001", "+306900000001")), 7));

        mvc.perform(get(BASE + "/subscriber"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(7))
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.items[0].id").value(ID))
                .andExpect(jsonPath("$.items[0].imsi").value("001010000000001"))
                .andExpect(jsonPath("$.items[0].phoneNumber").value("+306900000001"));
    }

    @Test
    void anEmptyPageStillHasItems() throws Exception {
        when(subscribers.list(10, 20)).thenReturn(new Slice<>(List.of(), 7));

        mvc.perform(get(BASE + "/subscriber").param("limit", "10").param("offset", "20"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"total\":7,\"limit\":10,\"offset\":20,\"items\":[]}"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "limit=0", "limit=501", "offset=-1", "limit=x" })
    void rejectsInvalidPaging(String query) throws Exception {
        mvc.perform(get(BASE + "/subscriber?" + query))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(subscribers);
    }

    @Test
    void getsASubscriber() throws Exception {
        when(subscribers.get(ID)).thenReturn(subscriber());

        mvc.perform(get(BASE + "/subscriber/" + ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID))
                .andExpect(jsonPath("$.imsi").value("001010000000001"))
                .andExpect(jsonPath("$.dataNetworks[0].dnn").value("internet"))
                // Unset optional fields are left out
                .andExpect(jsonPath("$.dataNetworks[0].sd").doesNotExist())
                .andExpect(jsonPath("$.extensions").doesNotExist());
    }

    @Test
    void anUnknownSubscriberIsAProblem404() throws Exception {
        when(subscribers.get(OTHER_ID)).thenThrow(new ResourceNotFoundException("Subscriber " + OTHER_ID + " not found"));

        mvc.perform(get(BASE + "/subscriber/" + OTHER_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("Subscriber " + OTHER_ID + " not found"))
                .andExpect(jsonPath("$.instance").value(BASE + "/subscriber/" + OTHER_ID));
    }

    @Test
    void anInvalidSubscriberIdIsAProblem400() throws Exception {
        when(subscribers.get("abc")).thenThrow(new InvalidRequestException("Invalid subscriber id 'abc'"));

        mvc.perform(get(BASE + "/subscriber/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid subscriber id 'abc'"))
                .andExpect(jsonPath("$.invalidParams").doesNotExist());
    }

    @Test
    void createsASubscriberAtItsId() throws Exception {
        when(subscribers.create(any())).thenReturn(ID);

        mvc.perform(json(post(BASE + "/subscriber"), SUBSCRIBER_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/tep-api/v1/subscriber/" + ID));
        verify(subscribers).create(argThat(s -> s.getImsi().equals("001010000000001")
                && s.getDataNetworks().get(0).getSd().equals("000001")));
    }

    @Test
    void namesTheMissingFieldsOfASubscriber() throws Exception {
        mvc.perform(json(post(BASE + "/subscriber"), "{\"imsi\":\"001010000000001\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(containsString("phoneNumber")))
                .andExpect(jsonPath("$.detail").value(containsString("dataNetworks")));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mvc.perform(json(post(BASE + "/subscriber"), "{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON));
    }

    @Test
    void anExistingSubscriberIsAProblem409() throws Exception {
        doThrow(new ResourceConflictException("exists")).when(subscribers).create(any());

        mvc.perform(json(post(BASE + "/subscriber"), SUBSCRIBER_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("exists"));
    }

    @Test
    void aCreateThatLostARaceIsAProblem409() throws Exception {
        doThrow(new DuplicateKeyException("E11000")).when(subscribers).create(any());

        mvc.perform(json(post(BASE + "/subscriber"), SUBSCRIBER_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Resource already exists"));
    }

    @Test
    void updatesAndDeletesASubscriber() throws Exception {
        mvc.perform(json(put(BASE + "/subscriber/" + ID), SUBSCRIBER_BODY))
                .andExpect(status().isNoContent());
        mvc.perform(delete(BASE + "/subscriber/" + ID))
                .andExpect(status().isNoContent());
        verify(subscribers).update(eq(ID), any());
        verify(subscribers).delete(ID);
    }

    @Test
    void retrievesASubscriberByThePhoneNumberOfItsDevice() throws Exception {
        when(subscribers.getByPhoneNumber("+306900000001")).thenReturn(subscriber());

        mvc.perform(json(post(BASE + "/subscriber/retrieve"), DEVICE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID))
                .andExpect(jsonPath("$.phoneNumber").value("+306900000001"));
    }

    @Test
    void getsTheStatusLocationAndSwapsOfASubscriberByThePhoneNumberOfItsDevice() throws Exception {
        when(statuses.status("+306900000001")).thenReturn(new TepSubscriberStatus(ConnectionStateEnum.CONNECTED)
                .pduSessions(List.of(new TepSubscriberStatusPduSession().dnn("internet"))));
        when(cells.locate("+306900000001")).thenReturn(new TepSubscriberLocation("00101-000000100")
                .center(new TepGeoPoint(38.2466, 21.7346)).radius(500.0)
                .lastUpdated(OffsetDateTime.of(2026, 11, 5, 9, 40, 42, 0, ZoneOffset.UTC)));
        when(swapEvents.get("+306900000001")).thenReturn(new TepSwaps().phoneNumber("+306900000001")
                .latestSimChange(OffsetDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC)));

        mvc.perform(json(post(BASE + "/subscriber/status"), DEVICE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionState").value("CONNECTED"))
                .andExpect(jsonPath("$.pduSessions[0].dnn").value("internet"));
        mvc.perform(json(post(BASE + "/subscriber/location"), DEVICE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cellId").value("00101-000000100"))
                .andExpect(jsonPath("$.center.lat").value(38.2466))
                .andExpect(jsonPath("$.lastUpdated").value("2026-11-05T09:40:42Z"));
        mvc.perform(json(post(BASE + "/subscriber/swaps"), DEVICE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestSimChange").value("2026-10-01T12:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "retrieve", "status", "location", "swaps" })
    void aLookupNeedsTheDevicesPhoneNumber(String lookup) throws Exception {
        mvc.perform(json(post(BASE + "/subscriber/" + lookup), "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(containsString("phoneNumber")));
        mvc.perform(post(BASE + "/subscriber/" + lookup))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(subscribers, statuses, cells, swapEvents);
    }

    @Test
    void anInvalidPhoneNumberIsAProblem400NamingItsField() throws Exception {
        when(statuses.status("abc"))
                .thenThrow(new InvalidRequestException("/phoneNumber", "Invalid phone number 'abc'"));

        mvc.perform(json(post(BASE + "/subscriber/status"), "{\"phoneNumber\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.invalidParams[0].param").value("/phoneNumber"));
    }

    @Test
    void aLookupIsNoLongerByAPhoneNumberInThePath() throws Exception {
        mvc.perform(get(BASE + "/subscriber/+306900000001/status"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(statuses);
    }

    @Test
    void echoesTheCorrelatorOnAnswersAndProblems() throws Exception {
        when(subscribers.get(ID)).thenReturn(subscriber());
        when(subscribers.get(OTHER_ID)).thenThrow(new ResourceNotFoundException("Subscriber " + OTHER_ID + " not found"));

        mvc.perform(get(BASE + "/subscriber/" + ID).header("x-correlator", "corr-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("x-correlator", "corr-1"));
        mvc.perform(get(BASE + "/subscriber/" + OTHER_ID).header("x-correlator", "corr-2"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("x-correlator", "corr-2"));
        mvc.perform(get(BASE + "/subscriber/" + ID))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("x-correlator"));
    }

    @Test
    void anInvalidCorrelatorIsAProblem400() throws Exception {
        mvc.perform(get(BASE + "/cell").header("x-correlator", "not valid!"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(header().doesNotExist("x-correlator"));
        verifyNoInteractions(cells);
    }

    @Test
    void aFailingOpen5gsFunctionIsAProblem500WithTheReason() throws Exception {
        when(statuses.status("+306900000001")).thenThrow(new UpstreamException("Open5GS AMF metrics unavailable"));

        mvc.perform(json(post(BASE + "/subscriber/status"), DEVICE_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Open5GS AMF metrics unavailable"));
    }

    @Test
    void anUnexpectedErrorIsAProblem500WithoutItsMessage() throws Exception {
        when(subscribers.get(ID)).thenThrow(new IllegalStateException("secret internals"));

        mvc.perform(get(BASE + "/subscriber/" + ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Unexpected server error"));
    }

    // --- Quality profiles

    @Test
    void readsQualityProfiles() throws Exception {
        TepQualityProfile profile = new TepQualityProfile("QOS_S", TepQualityProfile.StatusEnum.ACTIVE);
        when(profiles.list(50, 0)).thenReturn(new Slice<>(List.of(profile), 1));
        when(profiles.get("QOS_S")).thenReturn(profile);

        mvc.perform(get(BASE + "/quality-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("QOS_S"));
        mvc.perform(get(BASE + "/quality-profile/QOS_S"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void qualityProfilesCanNotBeChanged() throws Exception {
        mvc.perform(json(post(BASE + "/quality-profile"), PROFILE_BODY))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(PROBLEM_JSON));
        mvc.perform(json(put(BASE + "/quality-profile/QOS_S"), PROFILE_BODY))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete(BASE + "/quality-profile/QOS_S"))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(profiles);
    }

    @Test
    void rejectsAnInvalidProfileName() throws Exception {
        mvc.perform(get(BASE + "/quality-profile/no spaces"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("name")));
    }

    // --- Cells

    @Test
    void managesCells() throws Exception {
        TepCell cell = new TepCell(new TepPlmnId("999", "50"), "000000210", new TepGeoPoint(37.9838, 23.7275), 500.0)
                .id("99950-000000210");
        when(cells.list(50, 0)).thenReturn(new Slice<>(List.of(cell), 1));
        when(cells.get("99950-000000210")).thenReturn(cell);
        when(cells.create(any())).thenReturn("99950-000000210");

        mvc.perform(get(BASE + "/cell"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("99950-000000210"));
        mvc.perform(get(BASE + "/cell/99950-000000210"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plmn.mnc").value("50"))
                .andExpect(jsonPath("$.center.lat").value(37.9838));
        mvc.perform(json(post(BASE + "/cell"), CELL_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/tep-api/v1/cell/99950-000000210"));
        mvc.perform(json(put(BASE + "/cell/99950-000000210"), CELL_BODY))
                .andExpect(status().isNoContent());
        mvc.perform(delete(BASE + "/cell/99950-000000210"))
                .andExpect(status().isNoContent());

        verify(cells).create(argThat(c -> c.getNci().equals("000000210") && c.getCenter().getLon() == 23.7275
                && c.getRadius() == 500));
    }

    @Test
    void rejectsACellWithoutItsIdentityOrCoverageArea() throws Exception {
        mvc.perform(json(post(BASE + "/cell"), "{\"name\":\"cell-001\",\"plmn\":{\"mcc\":\"999\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("plmn.mnc")))
                .andExpect(jsonPath("$.detail").value(containsString("nci")))
                .andExpect(jsonPath("$.detail").value(containsString("center")))
                .andExpect(jsonPath("$.detail").value(containsString("radius")));
        verifyNoInteractions(cells);
    }

    @ParameterizedTest
    @ValueSource(strings = { "0000000210", "00000021", "0x0000210", "00000021G" })
    void rejectsAnNciThatIsNotNineHexDigits(String nci) throws Exception {
        mvc.perform(json(post(BASE + "/cell"), CELL_BODY.replace("000000210", nci)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("nci")));
        verifyNoInteractions(cells);
    }

    @ParameterizedTest
    @ValueSource(strings = { "{\"lat\":90.1,\"lon\":23.7275}", "{\"lat\":37.9838,\"lon\":-180.1}",
            "{\"lat\":37.9838}" })
    void rejectsACenterOffTheEarth(String center) throws Exception {
        mvc.perform(json(post(BASE + "/cell"), CELL_BODY.replace(CELL_CENTER, center)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("center.l")));
        verifyNoInteractions(cells);
    }
}
