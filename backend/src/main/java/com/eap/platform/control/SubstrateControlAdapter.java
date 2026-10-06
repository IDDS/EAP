package com.eap.platform.control;

import com.eap.platform.registry.AgentRuntimeInstance;
import com.eap.platform.substrate.SubstrateActorRef;
import com.eap.platform.substrate.SubstrateClient;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Executes lifecycle commands through the Substrate Control API instead of asking the agent to comply.
 * ACKED here means the operation finished (e.g. the actor is snapshotted and its worker released),
 * not merely that a request was received.
 */
@Component
public class SubstrateControlAdapter implements AgentControlAdapter {
    static final String DISPATCH_MODE = "SUBSTRATE_RUNTIME";

    private final SubstrateClient client;

    public SubstrateControlAdapter(SubstrateClient client) {
        this.client = client;
    }

    @Override
    public DispatchResult dispatch(AgentControlCommand command, AgentRuntimeInstance runtime) {
        SubstrateActorRef actor;
        try {
            actor = SubstrateActorRef.parse(runtime.getEndpoint());
        } catch (IllegalArgumentException exception) {
            return new DispatchResult(ControlCommandStatus.FAILED, DISPATCH_MODE, exception.getMessage(), null, null);
        }
        try {
            if (runtime.hasBoundIdentity()) {
                var current = client.inspect(actor);
                if (!java.util.Objects.equals(runtime.getBoundActorUid(),current.uid()) || !java.util.Objects.equals(runtime.getBoundActorTemplate(),current.template()))
                    return new DispatchResult(ControlCommandStatus.FAILED, DISPATCH_MODE, "实例身份或模板已改变，请重新复核纳管关系", null, null);
                if (runtime.getImportedActorUid()!=null && !java.util.Set.of("PAUSE", "RESUME", "RUN_HEALTH_CHECK").contains(command.commandType))
                    return new DispatchResult(ControlCommandStatus.FAILED, DISPATCH_MODE, "外部纳管实例仅支持休眠、恢复和状态检查", null, null);
            }
            SubstrateClient.ActorState state = switch (command.commandType) {
                case "PAUSE" -> client.suspend(actor);
                case "RESUME" -> client.resume(actor);
                case "REVERT" -> client.revert(actor);
                case "RUN_HEALTH_CHECK" -> client.get(actor);
                default -> null;
            };
            if (state == null) {
                return new DispatchResult(ControlCommandStatus.FAILED, DISPATCH_MODE,
                        "Substrate Runtime 暂不支持 " + command.commandType + "；该命令需要 Agent 自身处理", null, null);
            }
            return new DispatchResult(ControlCommandStatus.ACKED, DISPATCH_MODE,
                    "Actor " + actor.routingValue() + " 当前状态：" + state.state(), null, Instant.now());
        } catch (SubstrateClient.SubstrateException exception) {
            String reason = "RESOURCE_EXHAUSTED".equals(exception.code()) ? "没有空闲 worker，稍后重试或扩容" : exception.getMessage();
            return new DispatchResult(ControlCommandStatus.FAILED, DISPATCH_MODE,
                    "Substrate " + exception.code() + "：" + reason, null, null);
        }
    }
}
