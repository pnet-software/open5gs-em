package gr.pnet.em.tep.cell;

import java.util.Locale;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import gr.pnet.em.generated.tep.model.TepCell;
import gr.pnet.em.generated.tep.model.TepGeoPoint;
import gr.pnet.em.generated.tep.model.TepPlmnId;

/**
 * An NR cell and its coverage area, keyed by its NR Cell Global Identity: MCC and MNC, "-", then the 36-bit NCI
 * in nine lowercase hex digits (e.g. 99950-000000210).
 */
@Document("cells")
record CellDocument(@Id String id, String name, Plmn plmn, String nci, Point center, Double radius) {

    record Plmn(String mcc, String mnc) {
    }

    record Point(double lat, double lon) {

        TepGeoPoint toTep() {
            return new TepGeoPoint(lat, lon);
        }
    }

    /** The id of the cell with this NCI in this PLMN, given as MCC followed by MNC (as Open5GS reports it). */
    static String id(String plmn, long nci) {
        return id(plmn, "%09x".formatted(nci));
    }

    private static String id(String plmn, String nci) {
        return plmn + "-" + nci;
    }

    // The spec holds the NCI to nine hex digits, and the centre's coordinates to their ranges
    static CellDocument from(TepCell tep) {
        TepPlmnId plmn = tep.getPlmn();
        String nci = tep.getNci().toLowerCase(Locale.ROOT);
        TepGeoPoint center = tep.getCenter();
        return new CellDocument(id(plmn.getMcc() + plmn.getMnc(), nci), tep.getName(),
                new Plmn(plmn.getMcc(), plmn.getMnc()), nci, new Point(center.getLat(), center.getLon()),
                tep.getRadius());
    }

    TepCell toTep() {
        return new TepCell()
                .id(id)
                .name(name)
                .plmn(plmn == null ? null : new TepPlmnId(plmn.mcc(), plmn.mnc()))
                .nci(nci)
                .center(center == null ? null : center.toTep())
                .radius(radius);
    }
}
