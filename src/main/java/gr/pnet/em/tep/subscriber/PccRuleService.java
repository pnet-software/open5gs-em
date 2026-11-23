package gr.pnet.em.tep.subscriber;

import java.util.List;
import java.util.Set;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import gr.pnet.em.error.InvalidRequestException;
import gr.pnet.em.error.ResourceConflictException;
import gr.pnet.em.error.UpstreamException;
import gr.pnet.em.tep.status.Open5gsMetricsClient;
import gr.pnet.em.tep.status.UeSessions;

/**
 * Gives a UE's PDU session the PCC rule an AS session with QoS needs. The Open5GS PCF only authorizes an app session
 * whose 5QI (from its qos_profiles) has a PCC rule in the session's subscription data, and reads that data on every
 * request, so a rule added just before the request counts. A subscriber carries the rules its sessions have needed,
 * until a full session needs room: then the rules no QoS session uses go.
 */
@Service
public class PccRuleService {

    /** Open5GS's QoS flows in a PDU session, the default one included; every app session takes one of its own. */
    static final int MAX_QOS_FLOWS = 4;

    private static final Logger log = LoggerFactory.getLogger(PccRuleService.class);
    private static final String UE_IPV4_ADDR = "/ueIpv4Addr";

    private final MongoOperations mongo;
    private final Open5gsMetricsClient metrics;

    public PccRuleService(MongoOperations mongo, Open5gsMetricsClient metrics) {
        this.mongo = mongo;
        this.metrics = metrics;
    }

    /**
     * Makes sure the subscription data of the PDU session with this UE address, as the SMF reports it, has a PCC rule
     * for the 5QI. Open5GS takes at most eight rules in a session, so a session that has eight frees the rules no
     * QoS session uses before it gets a new one. Only rules without flows of their own go, and never the one for the
     * session's default 5QI; the default QoS flow comes from the session's qos, not from a rule. A session whose
     * eight rules are all needed is a conflict.
     *
     * @param inUse      the 5QIs the QoS sessions on this UE address use, whose rules must stay
     * @param newQosFlow whether the request needs a QoS flow of its own (a new app session). Open5GS takes at most
     *                   four in a PDU session, the default one included, so a session that has four is a conflict.
     */
    public void ensurePccRule(String ueIpv4Addr, int fiveQi, Set<Integer> inUse, boolean newQosFlow) {
        PduSession pduSession = pduSession(ueIpv4Addr);
        if (newQosFlow) {
            requireRoomForQosFlow(ueIpv4Addr, pduSession);
        }
        Document subscriber = subscriber(pduSession.supi());
        UeSessions.Snssai snssai = pduSession.pdu().snssai();
        String dnn = pduSession.pdu().dnn();
        List<Document> slices = Open5gsSubscriberMapper.documents(subscriber, "slice");
        for (int i = 0; i < slices.size(); i++) {
            if (snssai == null || snssai.sst() == null
                    || !Open5gsSubscriberMapper.isReportedSlice(slices.get(i), snssai.sst(), snssai.sd())) {
                continue;
            }
            List<Document> sessions = Open5gsSubscriberMapper.documents(slices.get(i), "session");
            for (int j = 0; j < sessions.size(); j++) {
                if (sessions.get(j).getString("name").equals(dnn)) {
                    addPccRule(subscriber, "slice." + i + ".session." + j, sessions.get(j), fiveQi, inUse);
                    return;
                }
            }
        }
        throw new UpstreamException("Subscriber " + pduSession.supi() + " has a PDU session on DNN " + dnn
                + " in slice " + snssai + ", which isn't in its subscription data");
    }

    private void addPccRule(Document subscriber, String session, Document sessionData, int fiveQi,
            Set<Integer> inUse) {
        if (Open5gsSubscriberMapper.hasPccRule(sessionData, fiveQi)) {
            return;
        }
        String imsi = subscriber.getString("imsi");
        String name = sessionData.getString("name");
        List<Document> rules = Open5gsSubscriberMapper.documents(sessionData, "pcc_rule");
        if (rules.size() >= Open5gsSubscriberMapper.MAX_PCC_RULES) {
            freeUnusedRules(subscriber, session, sessionData, fiveQi, inUse);
        }
        // Still the same session, still without a rule for the 5QI, and still with room for one
        Query unchanged = new Query(Criteria.where("_id").is(subscriber.get("_id"))
                .and(session + ".name").is(name)
                .and(session + ".pcc_rule.qos.index").ne(fiveQi)
                .and(session + ".pcc_rule." + (Open5gsSubscriberMapper.MAX_PCC_RULES - 1)).exists(false));
        Update push = new Update().push(session + ".pcc_rule", Open5gsSubscriberMapper.pccRule(fiveQi));
        if (mongo.updateFirst(unchanged, push, SubscriberService.COLLECTION).getModifiedCount() > 0) {
            // Logged by the subscriber's TEP id: never its IMSI
            log.info("Gave session {} of subscriber {} a PCC rule for 5QI {}", name,
                    SubscriberIds.id(subscriber.get("_id")), fiveQi);
            return;
        }
        // Changed meanwhile: fine if a concurrent request added the same rule
        Query added = new Query(Criteria.where("_id").is(subscriber.get("_id"))
                .and(session + ".name").is(name)
                .and(session + ".pcc_rule.qos.index").is(fiveQi));
        if (!mongo.exists(added, SubscriberService.COLLECTION)) {
            throw new ResourceConflictException("The subscription data of session " + name + " of subscriber " + imsi
                    + " changed while TEP was giving it a PCC rule for 5QI " + fiveQi + "; try again");
        }
    }

    // Removes the rules of a full session that nothing needs: those without flows (rules with flows are static, set
    // up as dedicated flows with the session), not for the session's default 5QI, and used by no QoS session
    private void freeUnusedRules(Document subscriber, String session, Document sessionData, int fiveQi,
            Set<Integer> inUse) {
        String name = sessionData.getString("name");
        Integer defaultFiveQi = qosIndex(sessionData);
        List<Document> unused = Open5gsSubscriberMapper.documents(sessionData, "pcc_rule").stream()
                .filter(rule -> rule.get("_id") != null && Open5gsSubscriberMapper.documents(rule, "flow").isEmpty())
                .filter(rule -> {
                    Integer ruleFiveQi = qosIndex(rule);
                    return ruleFiveQi != null && !ruleFiveQi.equals(defaultFiveQi) && !inUse.contains(ruleFiveQi);
                })
                .toList();
        if (unused.isEmpty()) {
            throw new ResourceConflictException("Session " + name + " of subscriber " + subscriber.getString("imsi")
                    + " already has " + Open5gsSubscriberMapper.MAX_PCC_RULES + " PCC rules, the most Open5GS takes, "
                    + "and none for 5QI " + fiveQi + ". All of them are in use by its QoS sessions, have flows of "
                    + "their own, or are for its default 5QI: end one of its QoS sessions first");
        }
        Query sameSession = new Query(Criteria.where("_id").is(subscriber.get("_id")).and(session + ".name").is(name));
        Update pull = new Update().pull(session + ".pcc_rule",
                new Document("_id", new Document("$in", unused.stream().map(rule -> rule.get("_id")).toList())));
        mongo.updateFirst(sameSession, pull, SubscriberService.COLLECTION);
        log.info("Freed the PCC rules for 5QIs {} of session {} of subscriber {}, which no QoS session uses",
                unused.stream().map(PccRuleService::qosIndex).toList(), name, SubscriberIds.id(subscriber.get("_id")));
    }

    private static Integer qosIndex(Document ruleOrSession) {
        Document qos = ruleOrSession.get("qos", Document.class);
        return qos != null && qos.get("index") instanceof Number index ? index.intValue() : null;
    }

    private static void requireRoomForQosFlow(String ueIpv4Addr, PduSession pduSession) {
        List<UeSessions.QosFlow> flows = pduSession.pdu().qosFlows();
        if (flows != null && flows.size() >= MAX_QOS_FLOWS) {
            throw new ResourceConflictException("The PDU session of " + pduSession.supi() + " with UE address "
                    + ueIpv4Addr + " already has " + flows.size() + " QoS flows, the most Open5GS takes: "
                    + "end one of its QoS sessions first");
        }
    }

    private PduSession pduSession(String ueIpv4Addr) {
        for (UeSessions.UeSession ue : metrics.ueSessions()) {
            for (UeSessions.Pdu pdu : ue.pdu() == null ? List.<UeSessions.Pdu>of() : ue.pdu()) {
                if (ueIpv4Addr.equals(pdu.ipv4())) {
                    return new PduSession(ue.supi(), pdu);
                }
            }
        }
        throw new InvalidRequestException(UE_IPV4_ADDR, "No PDU session has the UE address " + ueIpv4Addr
                + ": the UE isn't connected, or has another address");
    }

    private Document subscriber(String supi) {
        String imsi = supi != null && supi.startsWith("imsi-") ? supi.substring("imsi-".length()) : supi;
        Document subscriber = mongo.findOne(new Query(Criteria.where("imsi").is(imsi)), Document.class,
                SubscriberService.COLLECTION);
        if (subscriber == null) {
            throw new UpstreamException("Open5GS has a PDU session for " + supi + ", but no subscriber with that IMSI");
        }
        return subscriber;
    }

    private record PduSession(String supi, UeSessions.Pdu pdu) {
    }
}
