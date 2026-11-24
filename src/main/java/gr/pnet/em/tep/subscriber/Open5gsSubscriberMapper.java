package gr.pnet.em.tep.subscriber;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.bson.Document;
import org.bson.types.ObjectId;

import gr.pnet.em.error.InvalidRequestException;
import gr.pnet.em.generated.tep.model.TepSubscriber;
import gr.pnet.em.generated.tep.model.TepSubscriberDataNetwork;
import gr.pnet.em.generated.tep.model.TepSubscriberRef;
import gr.pnet.em.support.SliceDifferentiators;

/**
 * Maps between TEP subscribers and Open5GS subscriber documents (the {@code subscribers} collection, as the
 * WebUI writes it). Documents stay raw {@link Document}s so the fields TEP doesn't know survive an update.
 */
final class Open5gsSubscriberMapper {

    // Open5GS bitrate units: 0 bps, 1 Kbps, 2 Mbps, 3 Gbps, 4 Tbps
    private static final int KBPS = 1;
    private static final int GBPS = 3;
    // Open5GS session type 3: IPv4v6
    private static final int IPV4V6 = 3;
    // Open5GS ARP pre-emption capability and vulnerability: 1 disabled, 2 enabled
    private static final int PRE_EMPTION_DISABLED = 1;
    private static final int PRE_EMPTION_ENABLED = 2;
    /**
     * The most PCC rules Open5GS takes in a session: past it, loading the session's data fails an assertion and
     * the function loading it (the PCF, on every PDU and AS session) aborts.
     */
    static final int MAX_PCC_RULES = 8;

    private Open5gsSubscriberMapper() {
    }

    static TepSubscriberRef toRef(Document subscriber) {
        return new TepSubscriberRef(id(subscriber), subscriber.getString("imsi"), firstMsisdn(subscriber));
    }

    static TepSubscriber toTep(Document subscriber) {
        Document security = subscriber.get("security", new Document());
        return new TepSubscriber(subscriber.getString("imsi"), firstMsisdn(subscriber),
                security.getString("amf"), security.getString("k"), security.getString("opc"),
                dataNetworks(subscriber))
                .id(id(subscriber));
    }

    private static String id(Document subscriber) {
        Object id = subscriber.get("_id");
        return id == null ? null : SubscriberIds.id(id);
    }

    static String firstMsisdn(Document subscriber) {
        List<String> msisdn = subscriber.getList("msisdn", String.class);
        return msisdn == null || msisdn.isEmpty() ? null : msisdn.get(0);
    }

    /**
     * A new subscriber with the Open5GS defaults for everything TEP doesn't set. Its {@code _id}, which the WebUI
     * would also make an ObjectId, is its TEP id.
     */
    static Document newSubscriber(TepSubscriber tep) {
        return new Document("_id", new ObjectId())
                .append("schema_version", 1)
                .append("imsi", tep.getImsi())
                .append("msisdn", List.of(tep.getPhoneNumber()))
                .append("imeisv", List.of())
                .append("mme_host", List.of())
                .append("mme_realm", List.of())
                .append("purge_flag", List.of())
                .append("security", new Document("k", tep.getPermanentKeyValue())
                        .append("amf", tep.getAuthenticationManagementField())
                        .append("op", null)
                        .append("opc", tep.getOpcValue()))
                .append("ambr", ambr())
                .append("slice", mergeSlices(List.of(), tep.getDataNetworks()))
                .append("access_restriction_data", 32)
                .append("subscriber_status", 0)
                .append("operator_determined_barring", 0)
                .append("network_access_mode", 0)
                .append("subscribed_rau_tau_timer", 12)
                .append("__v", 0);
    }

    /** The subscriber's msisdn list with its first (TEP's) phone number replaced; any further ones are kept. */
    static List<String> replaceFirstMsisdn(Document subscriber, String phoneNumber) {
        List<String> current = subscriber.getList("msisdn", String.class, List.of());
        return Stream.concat(Stream.of(phoneNumber), current.stream().skip(1).filter(m -> !m.equals(phoneNumber)))
                .toList();
    }

    /**
     * The slices for a list of data networks. A slice or session that already exists (same sst and sd; same DNN
     * within the slice) is kept as it is, with its QoS, AMBR and PCC rules; new ones get the defaults, and no PCC
     * rules: an AS session adds the one it needs ({@link PccRuleService}).
     */
    static List<Document> mergeSlices(List<Document> existing, List<TepSubscriberDataNetwork> dataNetworks) {
        Map<String, Document> slices = new LinkedHashMap<>();
        Map<String, Document> existingSlices = new LinkedHashMap<>();
        for (TepSubscriberDataNetwork dataNetwork : dataNetworks == null ? List.<TepSubscriberDataNetwork>of()
                : dataNetworks) {
            int sst = sst(dataNetwork.getSst());
            String sd = dataNetwork.getSd();
            String key = sst + "|" + Objects.requireNonNullElse(sd, "");
            Document slice = slices.computeIfAbsent(key, k -> {
                Document current = existing.stream().filter(s -> isSlice(s, sst, sd)).findFirst().orElse(null);
                existingSlices.put(k, current);
                // A copy keeps the slice's own fields (_id, default_indicator); its sessions are rebuilt below
                return (current == null ? newSlice(sst, sd) : new Document(current))
                        .append("session", new ArrayList<Document>());
            });
            List<Document> sessions = documents(slice, "session");
            if (sessions.stream().anyMatch(s -> dataNetwork.getDnn().equals(s.getString("name")))) {
                continue;
            }
            Document current = existingSlices.get(key) == null ? null
                    : documents(existingSlices.get(key), "session").stream()
                            .filter(s -> dataNetwork.getDnn().equals(s.getString("name")))
                            .findFirst().orElse(null);
            sessions.add(current == null ? newSession(dataNetwork.getDnn()) : current);
        }
        List<Document> merged = new ArrayList<>(slices.values());
        // Open5GS needs one default slice; keep the subscriber's own choice if it survived
        if (!merged.isEmpty() && merged.stream().noneMatch(s -> s.getBoolean("default_indicator", false))) {
            merged.get(0).put("default_indicator", true);
        }
        return merged;
    }

    static List<Document> documents(Document parent, String field) {
        return parent.getList(field, Document.class, List.of());
    }

    private static List<TepSubscriberDataNetwork> dataNetworks(Document subscriber) {
        List<TepSubscriberDataNetwork> dataNetworks = new ArrayList<>();
        for (Document slice : documents(subscriber, "slice")) {
            String sst = slice.get("sst") == null ? null : String.valueOf(slice.get("sst"));
            for (Document session : documents(slice, "session")) {
                dataNetworks.add(
                        new TepSubscriberDataNetwork(session.getString("name"), sst).sd(slice.getString("sd")));
            }
        }
        return dataNetworks;
    }

    private static boolean isSlice(Document slice, int sst, String sd) {
        return slice.get("sst") instanceof Number number && number.intValue() == sst
                && Objects.equals(slice.getString("sd"), sd);
    }

    /** Whether the slice is the one the core reports a PDU session on, whose SD ffffff means it has none. */
    static boolean isReportedSlice(Document slice, int sst, String reportedSd) {
        return slice.get("sst") instanceof Number number && number.intValue() == sst
                && SliceDifferentiators.same(slice.getString("sd"), reportedSd);
    }

    private static int sst(String sst) {
        try {
            int value = Integer.parseInt(sst);
            if (value >= 0 && value <= 255) {
                return value;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new InvalidRequestException("Invalid sst '" + sst + "': expected a number from 0 to 255");
    }

    private static Document newSlice(int sst, String sd) {
        Document slice = new Document("_id", new ObjectId())
                .append("sst", sst)
                .append("default_indicator", false);
        if (sd != null) {
            slice.append("sd", sd);
        }
        return slice;
    }

    private static Document newSession(String dnn) {
        return new Document("_id", new ObjectId())
                .append("name", dnn)
                .append("type", IPV4V6)
                .append("qos", new Document("index", 9).append("arp", arp(8, 1, 1)))
                .append("ambr", ambr())
                .append("pcc_rule", new ArrayList<>());
    }

    /**
     * The PCC rule an AS session gives a session for its 5QI. The PCF only authorizes the AS session if the rule is
     * there, and takes its rates and ARP from the AS session where it gives them.
     *
     * <p>Only a GBR 5QI's rule has flow bit rates. The PCF falls back to the rule's rates for any the AS session
     * doesn't give, and the SMF signals GBR QoS flow information whenever a flow has rates, so a non-GBR rule has
     * none: its QoS flow then carries no MFBR or GFBR, as TS 23.501 clause 5.7.2.5 has it.
     *
     * <p>The rule's ARP is the fallback: the lowest priority level, no pre-emption of others, pre-emptable. The
     * Open5GS 2.8.0 PCF ignores the AS session's resPrio, preemptCap and preemptVuln, so every QoS flow there gets
     * this ARP. A PCF with AF-requested ARP (Open5GS PR #4793) applies those over it, and keeps it for any the AS
     * session doesn't give, or a resPrio missing from its reservation_priorities.
     */
    static Document pccRule(int fiveQi, boolean gbr) {
        Document qos = new Document("index", fiveQi).append("arp", arp(15, PRE_EMPTION_DISABLED, PRE_EMPTION_ENABLED));
        if (gbr) {
            // No value: unlimited
            qos.append("mbr", new Document("downlink", link(null, KBPS)).append("uplink", link(null, KBPS)))
                    .append("gbr", new Document("downlink", link(1, KBPS)).append("uplink", link(1, KBPS)));
        }
        return new Document("_id", new ObjectId())
                .append("flow", new ArrayList<>())
                .append("qos", qos);
    }

    /** Whether the session has a PCC rule for the 5QI. */
    static boolean hasPccRule(Document session, int fiveQi) {
        return documents(session, "pcc_rule").stream()
                .map(rule -> rule.get("qos", Document.class))
                .anyMatch(qos -> qos != null && qos.get("index") instanceof Number index && index.intValue() == fiveQi);
    }

    private static Document ambr() {
        return new Document("downlink", link(1, GBPS)).append("uplink", link(1, GBPS));
    }

    private static Document link(Integer value, int unit) {
        Document link = new Document();
        if (value != null) {
            link.append("value", value);
        }
        return link.append("unit", unit);
    }

    private static Document arp(int priorityLevel, int preEmptionCapability, int preEmptionVulnerability) {
        return new Document("priority_level", priorityLevel)
                .append("pre_emption_capability", preEmptionCapability)
                .append("pre_emption_vulnerability", preEmptionVulnerability);
    }
}
