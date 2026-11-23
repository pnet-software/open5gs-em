package gr.pnet.em.it;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.mongodb.MongoDBContainer;

import okhttp3.Protocol;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * The whole app against a real MongoDB, with the Open5GS network functions stubbed: the AMF/SMF metrics
 * ({@link MetricsStub}) and the PCF (an HTTP/2 prior-knowledge server that accepts every app session).
 * <p>
 * Mongo is TEST_MONGODB_URI if set (CI: a mongo service container, no Docker), otherwise a Testcontainers one.
 * Every IT shares one context and one database, which is emptied before each test. The quality profiles are
 * application-it.yaml's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
public abstract class IntegrationTest {

    private static final String DATABASE = "em_it_" + UUID.randomUUID().toString().substring(0, 8);
    private static final String MONGODB_URI = mongoUri();

    protected static final MetricsStub METRICS = MetricsStub.start();
    protected static final MockWebServer PCF = startPcf();
    protected static final String PCF_NOTIFICATIONS = "http://em.test/notifications";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected MongoTemplate mongo;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGODB_URI);
        registry.add("spring.mongodb.database", () -> DATABASE);
        registry.add("open5gs.metrics.amf-url", () -> METRICS.url("/ue-info"));
        registry.add("open5gs.metrics.smf-url", () -> METRICS.url("/pdu-info"));
        registry.add("open5gs.pcf.base-url", () -> PCF.url("/npcf-policyauthorization/v1").toString());
        registry.add("open5gs.pcf.notification-url", () -> PCF_NOTIFICATIONS);
        registry.add("logging.structured.format.console", () -> "");
    }

    @BeforeEach
    void resetState() throws InterruptedException {
        mongo.getDb().drop();
        METRICS.reset();
        while (PCF.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
            // drop what an earlier test left unread
        }
    }

    /** A fixture under src/test/resources/open5gs/. */
    protected static String fixture(String name) {
        try (InputStream in = IntegrationTest.class.getResourceAsStream("/open5gs/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected static Document fixtureDocument(String name) {
        return Document.parse(fixture(name));
    }

    protected static RecordedRequest takePcfRequest() throws InterruptedException {
        return PCF.takeRequest(5, TimeUnit.SECONDS);
    }

    private static String mongoUri() {
        String uri = System.getenv("TEST_MONGODB_URI");
        if (uri != null && !uri.isBlank()) {
            return uri;
        }
        // Stopped by Testcontainers when the test JVM exits
        MongoDBContainer container = new MongoDBContainer("mongo:7");
        container.start();
        return container.getConnectionString();
    }

    private static MockWebServer startPcf() {
        MockWebServer server = new MockWebServer();
        server.setProtocols(List.of(Protocol.H2_PRIOR_KNOWLEDGE));
        AtomicInteger appSessionIds = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                if (path.endsWith("/app-sessions")) {
                    return new MockResponse().setResponseCode(201)
                            .setHeader("Location", server.url("/npcf-policyauthorization/v1/app-sessions/"
                                    + appSessionIds.incrementAndGet()))
                            .setHeader("Content-Type", "application/json")
                            .setBody("{}");
                }
                if (path.endsWith("/delete")) {
                    return new MockResponse().setResponseCode(204);
                }
                if ("PATCH".equals(request.getMethod())) {
                    // Open5GS echoes the patch it applied
                    return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                            .setBody(request.getBody().clone().readUtf8());
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        try {
            server.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return server;
    }
}
