package com.eap.platform.evaluation;

import com.eap.platform.common.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class EvaluationCalibrationHistoryService {
    private final EvaluationCalibrationRunRepository repository;
    private final ObjectMapper objectMapper;

    public EvaluationCalibrationHistoryService(EvaluationCalibrationRunRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(EvaluationDtos.CalibrationRunDetail snapshot) {
        var report = snapshot.calibration();
        var run = new EvaluationCalibrationRun();
        run.runId = report.runId();
        run.datasetKey = report.datasetKey();
        run.datasetVersion = report.datasetVersion();
        run.requestedLimit = report.requestedLimit();
        run.totalItems = report.totalItems();
        run.evaluatedItems = report.evaluatedItems();
        run.failedItems = report.failedItems();
        run.calculatedAt = report.calculatedAt();
        try {
            run.snapshotJson = objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存校准历史", exception);
        }
        repository.save(run);
    }

    @Transactional(readOnly = true)
    public List<EvaluationDtos.CalibrationRunSummary> list(String datasetKey, int requestedLimit) {
        return repository.findByDatasetKeyOrderByCalculatedAtDescRunIdDesc(normalize(datasetKey),
                        PageRequest.of(0, Math.max(1, Math.min(requestedLimit, 100)))).stream()
                .map(run -> new EvaluationDtos.CalibrationRunSummary(run.getRunId(), run.getDatasetKey(),
                        run.getDatasetVersion(), run.getRequestedLimit(), run.getTotalItems(), run.getEvaluatedItems(),
                        run.getFailedItems(), run.getCalculatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public EvaluationDtos.CalibrationRunDetail get(String datasetKey, String runId) {
        var run = repository.findByRunIdAndDatasetKey(runId, normalize(datasetKey))
                .orElseThrow(() -> new BusinessException("校准运行记录不存在"));
        try {
            return objectMapper.readValue(run.snapshotJson, EvaluationDtos.CalibrationRunDetail.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取校准历史", exception);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
