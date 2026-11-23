package gr.pnet.em.tep.cell;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import gr.pnet.em.generated.tep.model.TepCell;
import gr.pnet.em.generated.tep.model.TepGeoPoint;
import gr.pnet.em.generated.tep.model.TepPlmnId;

class CellDocumentTest {

    private static final TepGeoPoint CENTER = new TepGeoPoint(37.9838, 23.7275);

    @Test
    void isKeyedByItsNrCellGlobalIdentity() {
        CellDocument document = CellDocument.from(
                new TepCell(new TepPlmnId("999", "50"), "000000210", CENTER, 500.0).name("cell-001"));

        assertThat(document).isEqualTo(new CellDocument("99950-000000210", "cell-001",
                new CellDocument.Plmn("999", "50"), "000000210", new CellDocument.Point(37.9838, 23.7275), 500.0));
    }

    @Test
    void keepsTheNciInLowercase() {
        CellDocument document = CellDocument.from(new TepCell(new TepPlmnId("999", "50"), "FFFFFFFFF", CENTER, 500.0));

        assertThat(document.id()).isEqualTo("99950-fffffffff");
        assertThat(document.nci()).isEqualTo("fffffffff");
    }

    @Test
    void keepsAThreeDigitMncApart() {
        TepCell cell = new TepCell(new TepPlmnId("310", "410"), "000000100", CENTER, 500.0);

        assertThat(CellDocument.from(cell).id()).isEqualTo("310410-000000100");
    }

    @Test
    void givesTheIdOfTheCellTheAmfReports() {
        // Open5GS reports the PLMN as MCC followed by MNC, and the NCI as a number
        assertThat(CellDocument.id("00101", 256)).isEqualTo("00101-000000100");
        assertThat(CellDocument.id("99950", (1L << 36) - 1)).isEqualTo("99950-fffffffff");
    }

    @Test
    void readsBackAsTheCellItWasMadeFrom() {
        TepCell cell = new TepCell(new TepPlmnId("999", "50"), "000000210", CENTER, 500.0).name("cell-001");

        TepCell read = CellDocument.from(cell).toTep();

        assertThat(read).isEqualTo(cell.id("99950-000000210"));
    }
}
