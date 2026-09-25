package org.example.agent.observability;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import org.example.checkpoint.CheckpointRecord;
import org.example.checkpoint.CheckpointService;
import org.example.checkpoint.PendingTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 把每次工具调用实时汇报出去。
 *
 * <p>多 Agent 编排一次要跑一两分钟，期间只有工具调用是可观测的外部动作。拦截在这一层，
 * 调用方就能把"Agent 现在在查什么"推给前端，而不必等整轮编排结束。
 */
public class ToolCallProgressInterceptor extends ToolInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(ToolCallProgressInterceptor.class);

    private final Consumer<String> reporter;
    private final AtomicInteger step = new AtomicInteger();
    private final CheckpointService checkpoints;
    private final CheckpointRecord checkpoint;
    private final CheckpointRecord.RecoveryAudit recoveryAudit;

    public ToolCallProgressInterceptor(Consumer<String> reporter) {
        this(reporter, null, null, null);
    }

    public ToolCallProgressInterceptor(Consumer<String> reporter, CheckpointService checkpoints,
                                       CheckpointRecord checkpoint,
                                       CheckpointRecord.RecoveryAudit recoveryAudit) {
        this.reporter = reporter;
        this.checkpoints = checkpoints;
        this.checkpoint = checkpoint;
        this.recoveryAudit = recoveryAudit;
    }

    @Override
    public String getName() {
        return "tool_call_progress";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        String tool = request.getToolName() != null ? request.getToolName().trim() : "未知工具";
        String arguments = request.getArguments();
        int seq = step.incrementAndGet();
        long startedAt = System.currentTimeMillis();

        PendingTool tracked = null;
        if (checkpoints != null && checkpoint != null) {
            PendingTool previous = checkpoints.findTool(checkpoint, tool, arguments).orElse(null);
            if (previous != null && previous.getStatus() == PendingTool.Status.SUCCEEDED) {
                if (recoveryAudit != null) {
                    recoveryAudit.getSkippedTools().add(tool);
                    checkpoints.update(checkpoint, ignored -> { });
                }
                report("第 " + seq + " 步 · " + tool + " 使用 checkpoint 结果");
                return ToolCallResponse.of(request.getToolCallId(), tool, previous.getResult());
            }
            if (previous != null && previous.getStatus() == PendingTool.Status.FAILED
                    && !checkpoints.mayRetry(previous)) {
                throw new IllegalStateException(tool + " 已达到 checkpoint 重试上限");
            }
            if (previous != null && recoveryAudit != null) {
                if (previous.getStatus() == PendingTool.Status.UNKNOWN) {
                    recoveryAudit.getVerifiedTools().add(tool);
                } else if (previous.getStatus() == PendingTool.Status.FAILED) {
                    recoveryAudit.getRetriedTools().add(tool);
                }
            }
            tracked = checkpoints.beforeTool(checkpoint, tool, arguments);
        }

        report("第 " + seq + " 步 · 正在调用 " + tool);
        try {
            ToolCallResponse response = handler.call(request);
            if (tracked != null) {
                checkpoints.toolSucceeded(checkpoint, tracked, response.getResult());
            }
            report("第 " + seq + " 步 · " + tool + " 已返回（" + (System.currentTimeMillis() - startedAt) + " ms）");
            return response;
        } catch (RuntimeException e) {
            if (tracked != null) {
                checkpoints.toolFailed(checkpoint, tracked, e.getMessage());
            }
            report("第 " + seq + " 步 · " + tool + " 调用失败，Agent 将改用其他证据");
            throw e;
        }
    }

    private void report(String text) {
        try {
            reporter.accept(text);
        } catch (Exception e) {
            logger.debug("上报工具调用进度失败: {}", e.getMessage());
        }
    }
}
