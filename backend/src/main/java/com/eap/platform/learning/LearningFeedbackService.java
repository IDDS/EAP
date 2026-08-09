package com.eap.platform.learning;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class LearningFeedbackService {
    private final LearningFeedbackRepository repository;
    private final AgentRepository agentRepository;

    public LearningFeedbackService(LearningFeedbackRepository repository, AgentRepository agentRepository) {
        this.repository = repository;
        this.agentRepository = agentRepository;
    }

    @Transactional(readOnly = true)
    public List<LearningFeedbackDtos.FeedbackResponse> list() {
        return repository.findAll().stream().map(LearningFeedbackDtos::from).toList();
    }

    @Transactional
    public LearningFeedbackDtos.FeedbackResponse create(LearningFeedbackDtos.FeedbackRequest request) {
        if (!agentRepository.existsById(request.agentId())) {
            throw new BusinessException("智能体不存在");
        }
        LearningFeedback feedback = new LearningFeedback();
        feedback.agentId = request.agentId();
        feedback.feedbackType = request.feedbackType();
        feedback.rating = request.rating();
        feedback.summary = request.summary();
        feedback.improvementAction = request.improvementAction();
        return LearningFeedbackDtos.from(repository.save(feedback));
    }

    @Transactional
    public LearningFeedbackDtos.FeedbackResponse updateStatus(Long id, LearningFeedbackStatus status) {
        LearningFeedback feedback = repository.findById(id).orElseThrow(() -> new BusinessException("学习反馈不存在"));
        feedback.status = status;
        return LearningFeedbackDtos.from(feedback);
    }
}

