package gr.pnet.em.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

class CorrelatorFilterTest {

    private final CorrelatorFilter filter = new CorrelatorFilter();
    private final AtomicReference<String> logged = new AtomicReference<>();
    private final FilterChain chain = (request, response) -> logged.set(MDC.get(CorrelatorFilter.MDC_KEY));

    private MockHttpServletResponse filter(String correlator) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/tep-api/v1/cell");
        if (correlator != null) {
            request.addHeader(CorrelatorFilter.HEADER, correlator);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void echoesTheCorrelatorAndLogsTheRequestUnderIt() throws Exception {
        MockHttpServletResponse response = filter("b4333c46-49c0-4f62-80d7-f0ef930f1c46");

        assertThat(response.getHeader(CorrelatorFilter.HEADER)).isEqualTo("b4333c46-49c0-4f62-80d7-f0ef930f1c46");
        assertThat(logged.get()).isEqualTo("b4333c46-49c0-4f62-80d7-f0ef930f1c46");
        assertThat(MDC.get(CorrelatorFilter.MDC_KEY)).isNull();
    }

    @Test
    void withoutACorrelatorTheLogsGetAGeneratedOne() throws Exception {
        MockHttpServletResponse response = filter(null);

        assertThat(response.getHeader(CorrelatorFilter.HEADER)).isNull();
        assertThat(logged.get()).hasSize(36);
        assertThat(MDC.get(CorrelatorFilter.MDC_KEY)).isNull();
    }

    @Test
    void anInvalidCorrelatorIsNeitherEchoedNorLogged() throws Exception {
        MockHttpServletResponse response = filter("not valid!");

        assertThat(response.getHeader(CorrelatorFilter.HEADER)).isNull();
        assertThat(logged.get()).isNotEqualTo("not valid!").hasSize(36);
    }
}
