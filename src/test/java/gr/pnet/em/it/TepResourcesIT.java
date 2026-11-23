package gr.pnet.em.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Quality profiles, which come from configuration, and cells, a collection this app owns. */
class TepResourcesIT extends IntegrationTest {

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void servesTheConfiguredQualityProfiles() throws Exception {
        mvc.perform(get("/tep-api/v1/quality-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].name").value("QOS_S"))
                .andExpect(jsonPath("$.items[1].status").value("DEPRECATED"));
        mvc.perform(get("/tep-api/v1/quality-profile/QOS_S"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"name":"QOS_S","fiveQi":67,"description":"GBR flow with 5QI 67","status":"ACTIVE",
                         "maxUpstreamRate":{"value":5,"unit":"Mbps"},"maxDownstreamRate":{"value":10,"unit":"Mbps"},
                         "targetMinDownstreamRate":{"value":2,"unit":"Mbps"},"maxDuration":{"value":1,"unit":"HOURS"},
                         "priority":20,"packetErrorLossRate":2,"preemptionVulnerability":"NOT_PREEMPTABLE"}"""));
        mvc.perform(get("/tep-api/v1/quality-profile").param("limit", "1").param("offset", "1"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("QOS_D"));
        mvc.perform(get("/tep-api/v1/quality-profile/QOS_X"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Quality profile QOS_X not found"));
    }

    @Test
    void doesNotChangeQualityProfiles() throws Exception {
        String profile = "{\"name\":\"QOS_X\",\"status\":\"ACTIVE\"}";

        mvc.perform(json(post("/tep-api/v1/quality-profile"), profile)).andExpect(status().isMethodNotAllowed());
        mvc.perform(json(put("/tep-api/v1/quality-profile/QOS_S"), profile)).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/tep-api/v1/quality-profile/QOS_S")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/tep-api/v1/quality-profile")).andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void managesCells() throws Exception {
        String cell = """
                {"name":"cell-001","plmn":{"mcc":"999","mnc":"50"},"nci":"00000021A",
                 "center":{"lat":37.9838,"lon":23.7275},"radius":500}""";
        mvc.perform(json(post("/tep-api/v1/cell"), cell))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/tep-api/v1/cell/99950-00000021a"));
        mvc.perform(json(post("/tep-api/v1/cell"), cell)).andExpect(status().isConflict());

        // Keyed by the NCGI, with the NCI in lowercase
        Document stored = mongo.findById("99950-00000021a", Document.class, "cells");
        assertThat(stored)
                .containsEntry("plmn", new Document("mcc", "999").append("mnc", "50"))
                .containsEntry("nci", "00000021a")
                .containsEntry("center", new Document("lat", 37.9838).append("lon", 23.7275));
        mvc.perform(get("/tep-api/v1/cell/99950-00000021a"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"id":"99950-00000021a","name":"cell-001","plmn":{"mcc":"999","mnc":"50"},"nci":"00000021a",
                         "center":{"lat":37.9838,"lon":23.7275},"radius":500}"""));

        mvc.perform(json(put("/tep-api/v1/cell/99950-00000021a"), cell.replace("\"radius\":500", "\"radius\":800")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/tep-api/v1/cell/99950-00000021a")).andExpect(jsonPath("$.radius").value(800.0));
        mvc.perform(json(put("/tep-api/v1/cell/99950-00000021a"), cell.replace("00000021A", "00000021B")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("The plmn and nci give the cell id 99950-00000021b, not the path id 99950-00000021a"));
        mvc.perform(get("/tep-api/v1/cell")).andExpect(jsonPath("$.total").value(1));

        mvc.perform(delete("/tep-api/v1/cell/99950-00000021a")).andExpect(status().isNoContent());
        mvc.perform(delete("/tep-api/v1/cell/99950-00000021a")).andExpect(status().isNotFound());
    }

    @Test
    void validatesCells() throws Exception {
        String cell = """
                {"plmn":{"mcc":"999","mnc":"50"},"nci":"000000210","center":{"lat":37.9838,"lon":23.7275},"radius":500}""";

        mvc.perform(json(post("/tep-api/v1/cell"), cell.replace("37.9838", "91")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("center.lat")));
        mvc.perform(json(post("/tep-api/v1/cell"), cell.replace("000000210", "0x210")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("nci")));
        mvc.perform(json(post("/tep-api/v1/cell"), cell.replace(",\"radius\":500", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("radius")));
        mvc.perform(get("/tep-api/v1/cell")).andExpect(jsonPath("$.total").value(0));
    }
}
