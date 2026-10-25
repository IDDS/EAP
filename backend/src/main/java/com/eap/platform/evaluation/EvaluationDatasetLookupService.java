package com.eap.platform.evaluation;

import com.eap.platform.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

import static com.eap.platform.evaluation.EvaluationDtos.DatasetLookupKind.*;
import static com.eap.platform.evaluation.EvaluationDtos.DatasetLookupState.*;

@Service
@Transactional(readOnly = true)
public class EvaluationDatasetLookupService {
    private final EvaluationDatasetRepository datasets;
    private final EvaluationDatasetItemRepository items;

    public EvaluationDatasetLookupService(EvaluationDatasetRepository datasets, EvaluationDatasetItemRepository items) {
        this.datasets = datasets;
        this.items = items;
    }

    public EvaluationDtos.DatasetLookupResponse dataset(String datasetKey) {
        String key = key(datasetKey, true);
        var dataset = datasets.findByDatasetKey(key).orElse(null);
        if (dataset == null) return new EvaluationDtos.DatasetLookupResponse(DATASET, NOT_FOUND, key, null, null, null);
        // The production collation can equate different Unicode/case spellings.
        // Never present that as an exact match or expose another key's metadata.
        if (!key.equals(dataset.datasetKey)) return new EvaluationDtos.DatasetLookupResponse(DATASET, KEY_MISMATCH, key, null, null, null);
        return new EvaluationDtos.DatasetLookupResponse(DATASET, FOUND, key, null, dataset.id, dataset.createdAt);
    }

    public EvaluationDtos.DatasetLookupResponse sample(String datasetKey, String itemKey) {
        String key = key(datasetKey, true), sampleKey = key(itemKey, false);
        var dataset = datasets.findByDatasetKey(key).orElse(null);
        if (dataset == null) return new EvaluationDtos.DatasetLookupResponse(SAMPLE, NOT_FOUND, key, sampleKey, null, null);
        if (!key.equals(dataset.datasetKey)) return new EvaluationDtos.DatasetLookupResponse(SAMPLE, KEY_MISMATCH, key, sampleKey, null, null);
        var item = items.findByDatasetIdAndItemKey(dataset.id, sampleKey).orElse(null);
        if (item == null) return new EvaluationDtos.DatasetLookupResponse(SAMPLE, NOT_FOUND, key, sampleKey, null, null);
        if (!sampleKey.equals(item.itemKey)) return new EvaluationDtos.DatasetLookupResponse(SAMPLE, KEY_MISMATCH, key, sampleKey, null, null);
        return new EvaluationDtos.DatasetLookupResponse(SAMPLE, FOUND, key, sampleKey, item.id, item.createdAt);
    }

    private String key(String value, boolean uppercase) {
        if (value == null || value.trim().isEmpty() || value.length() > 128) throw new BusinessException("查询标识必须为 1 至 128 个字符");
        String key = uppercase ? value.trim().toUpperCase(Locale.ROOT) : value.trim();
        if (key.length() > 128) throw new BusinessException("规范化后的查询标识不能超过 128 个字符");
        return key;
    }
}
