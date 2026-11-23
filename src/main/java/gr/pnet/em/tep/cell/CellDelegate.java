package gr.pnet.em.tep.cell;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import gr.pnet.em.generated.tep.CellsApiDelegate;
import gr.pnet.em.generated.tep.model.TepCell;
import gr.pnet.em.generated.tep.model.TepCellPage;
import gr.pnet.em.support.Locations;
import gr.pnet.em.support.Slice;

@Service
public class CellDelegate implements CellsApiDelegate {

    private final CellService cells;

    public CellDelegate(CellService cells) {
        this.cells = cells;
    }

    @Override
    public ResponseEntity<TepCellPage> listCells(String xCorrelator, Integer limit, Integer offset) {
        Slice<TepCell> page = cells.list(limit, offset);
        return ResponseEntity.ok(new TepCellPage(page.total(), limit, offset, page.items()));
    }

    @Override
    public ResponseEntity<TepCell> getCell(String id, String xCorrelator) {
        return ResponseEntity.ok(cells.get(id));
    }

    @Override
    public ResponseEntity<Void> createCell(TepCell cell, String xCorrelator) {
        return Locations.created(cells.create(cell));
    }

    @Override
    public ResponseEntity<Void> updateCell(String id, TepCell cell, String xCorrelator) {
        cells.update(id, cell);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> deleteCell(String id, String xCorrelator) {
        cells.delete(id);
        return ResponseEntity.noContent().build();
    }
}
