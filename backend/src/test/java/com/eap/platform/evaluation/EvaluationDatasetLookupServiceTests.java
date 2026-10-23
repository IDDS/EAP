package com.eap.platform.evaluation;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.eap.platform.evaluation.EvaluationDtos.DatasetLookupState.*;

class EvaluationDatasetLookupServiceTests {
    private final EvaluationDatasetRepository datasets = mock(EvaluationDatasetRepository.class);
    private final EvaluationDatasetItemRepository items = mock(EvaluationDatasetItemRepository.class);
    private final EvaluationDatasetLookupService service = new EvaluationDatasetLookupService(datasets, items);

    @Test void queriesOnlyTheOriginalDatasetAndItemWithoutListingOrWriting() {
        var dataset = new EvaluationDataset(); dataset.id = 4L; dataset.datasetKey = "DS/汉";
        var item = new EvaluationDatasetItem(); item.id = 501L; item.itemKey = "Case/?#汉"; item.createdAt = Instant.parse("2026-10-16T00:00:00Z");
        when(datasets.findByDatasetKey("DS/汉")).thenReturn(Optional.of(dataset));
        when(items.findByDatasetIdAndItemKey(4L, "Case/?#汉")).thenReturn(Optional.of(item));
        var response = service.sample(" ds/汉 ", " Case/?#汉 ");
        assertThat(response.state()).isEqualTo(FOUND); assertThat(response.recordId()).isEqualTo(501L);
        assertThat(response.itemKey()).isEqualTo("Case/?#汉"); assertThat(response.createdAt()).isEqualTo(item.createdAt);
        verify(datasets).findByDatasetKey("DS/汉"); verify(items).findByDatasetIdAndItemKey(4L, "Case/?#汉");
        verifyNoMoreInteractions(datasets, items);
    }
    @Test void collationEquivalentDatasetCannotExposeAnotherRecord() {
        var dataset = new EvaluationDataset(); dataset.id = 9L; dataset.datasetKey = "DŚ";
        when(datasets.findByDatasetKey("DS")).thenReturn(Optional.of(dataset));
        assertThat(service.dataset("ds").state()).isEqualTo(KEY_MISMATCH);
        var response = service.sample("ds", "case");
        assertThat(response.state()).isEqualTo(KEY_MISMATCH); assertThat(response.recordId()).isNull(); assertThat(response.createdAt()).isNull();
        verifyNoInteractions(items);
    }
    @Test void collationEquivalentItemDoesNotBecomeAnExactMatch() {
        var dataset = new EvaluationDataset(); dataset.id = 4L; dataset.datasetKey = "DS";
        var item = new EvaluationDatasetItem(); item.id = 501L; item.itemKey = "CASE";
        when(datasets.findByDatasetKey("DS")).thenReturn(Optional.of(dataset));
        when(items.findByDatasetIdAndItemKey(4L, "case")).thenReturn(Optional.of(item));
        var response = service.sample("DS", "case");
        assertThat(response.state()).isEqualTo(KEY_MISMATCH); assertThat(response.recordId()).isNull();
    }
    @Test void absentDatasetReturnsNotFoundWithoutQueryingOtherDatasets() {
        when(datasets.findByDatasetKey("MISSING")).thenReturn(Optional.empty());
        assertThat(service.dataset("missing").state()).isEqualTo(NOT_FOUND);
        assertThat(service.sample("missing", "case").state()).isEqualTo(NOT_FOUND);
        verifyNoInteractions(items);
    }
    @Test void rejectsBlankOverlongAndExpandedKeysBeforeQuerying() {
        for (String key : new String[]{null, " ", "x".repeat(129), "ß".repeat(128)}) assertThatThrownBy(() -> service.dataset(key)).isInstanceOf(com.eap.platform.common.BusinessException.class);
        for (String key : new String[]{null, " ", "x".repeat(129)}) assertThatThrownBy(() -> service.sample("DS", key)).isInstanceOf(com.eap.platform.common.BusinessException.class);
        verifyNoInteractions(datasets, items);
    }
}
