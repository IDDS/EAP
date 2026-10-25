package com.eap.platform.safety;

import com.eap.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SafetyAssociationTests {
    private final SafetySessionRepository sessions = mock(SafetySessionRepository.class);
    private final SafetyEventRepository events = mock(SafetyEventRepository.class);
    private final SafetyDecisionLogRepository decisions = mock(SafetyDecisionLogRepository.class);
    private final SafetyArtifactRepository artifacts = mock(SafetyArtifactRepository.class);
    private final GenseeSafetyService service = new GenseeSafetyService(sessions, events, decisions, artifacts, false, "");

    private SafetySession session(String requested, String stored) {
        var s = new SafetySession(); s.id = 1L; s.sessionKey = stored; s.rootAgentId = 7L; s.objective = "original";
        when(sessions.findBySessionKey(requested)).thenReturn(Optional.of(s));
        return s;
    }
    private SafetyDtos.SafetyEventRequest evaluation(String key) {
        return new SafetyDtos.SafetyEventRequest(key, 7L, null, SafetyEventType.TOOL_CALL,
                "lookup", "sandbox/item", "synthetic", "{}");
    }
    private SafetyDtos.ArtifactRequest artifact(String key) {
        return new SafetyDtos.ArtifactRequest(key, 9L, "artifact://synthetic/item", "REPORT", "synthetic-hash");
    }

    @ParameterizedTest @ValueSource(strings = {"SESSION", "séssion", "session "})
    void collationAliasesCannotRenameSessionOrWriteEventsAndArtifacts(String alias) {
        var original = session(alias, "session");
        assertThatThrownBy(() -> service.createSession(new SafetyDtos.SessionRequest(alias, 88L, "replacement")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("标识不匹配");
        assertThat(original.sessionKey).isEqualTo("session");
        assertThat(original.rootAgentId).isEqualTo(7L);
        assertThat(original.objective).isEqualTo("original");
        assertThatThrownBy(() -> service.evaluate(evaluation(alias))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.evaluateMetadata(evaluation(alias))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.registerArtifact(artifact(alias))).isInstanceOf(BusinessException.class);
        verify(sessions, never()).save(any());
        verifyNoInteractions(events, decisions, artifacts);
    }

    @ParameterizedTest @ValueSource(strings = {"another-session", "SESSION", "séssion", "session "})
    void eventAssociationMustMatchExactlyEvenForLegacyRows(String eventKey) {
        session("session", "session");
        var event = new SafetyEvent(); event.id = 9L; event.sessionKey = eventKey;
        when(events.findById(9L)).thenReturn(Optional.of(event));
        assertThatThrownBy(() -> service.registerArtifact(artifact("session")))
                .isInstanceOf(BusinessException.class).hasMessage("安全事件不属于指定会话");
        verifyNoInteractions(artifacts, decisions);
        verify(events, never()).save(any());
    }

    @Test void missingSessionRejectsArtifactBeforeEventReadOrWrite() {
        assertThatThrownBy(() -> service.registerArtifact(artifact("missing")))
                .isInstanceOf(BusinessException.class).hasMessage("安全会话不存在");
        verifyNoInteractions(events, decisions, artifacts);
    }

    @Test void missingEventDoesNotCreateArtifact() {
        session("session", "session");
        assertThatThrownBy(() -> service.registerArtifact(artifact("session")))
                .isInstanceOf(BusinessException.class).hasMessage("安全事件不存在");
        verifyNoInteractions(artifacts, decisions);
    }
}
