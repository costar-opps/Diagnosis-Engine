package org.example.agent.observability;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
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

    public ToolCallProgressInterceptor(Consumer<String> reporter) {
        this.reporter = reporter;
    }

    @Override
    public String getName() {
        return "tool_call_progress";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        String tool = request.getToolName() != null ? request.getToolName().trim() : "未知工具";
        int seq = step.incrementAndGet();
        long startedAt = System.currentTimeMillis();

        report("第 " + seq + " 步 · 正在调用 " + tool);
        try {
            ToolCallResponse response = handler.call(request);
            report("第 " + seq + " 步 · " + tool + " 已返回（" + (System.currentTimeMillis() - startedAt) + " ms）");
            return response;
        } catch (RuntimeException e) {
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
