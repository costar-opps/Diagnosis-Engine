package org.example.config;

import io.modelcontextprotocol.client.McpAsyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP 会话保活。
 *
 * <p>cls-mcp-server 会主动踢掉空闲会话，会话一旦失效，所有 MCP 工具调用都会返回
 * {@code 400 Bad Request}，Agent 只能拿着残缺的证据编报告。这里按固定间隔发送 ping
 * 让会话始终处于活跃状态；ping 失败时再尝试重新握手。
 */
@Component
public class McpSessionKeepAlive {

    private static final Logger logger = LoggerFactory.getLogger(McpSessionKeepAlive.class);

    private static final Duration PING_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration INITIALIZE_TIMEOUT = Duration.ofSeconds(30);

    private final ObjectProvider<List<McpAsyncClient>> clientsProvider;
    private final AtomicBoolean firstRun = new AtomicBoolean(true);

    public McpSessionKeepAlive(ObjectProvider<List<McpAsyncClient>> clientsProvider) {
        this.clientsProvider = clientsProvider;
    }

    /**
     * 间隔必须显著小于服务端的空闲超时（当前实测约 10 分钟），否则保活会赶不上淘汰。
     */
    @Scheduled(
            initialDelayString = "${mcp.keep-alive.interval-ms:120000}",
            fixedDelayString = "${mcp.keep-alive.interval-ms:120000}")
    public void keepSessionsAlive() {
        List<McpAsyncClient> clients = clientsProvider.getIfAvailable();
        if (clients == null || clients.isEmpty()) {
            if (firstRun.compareAndSet(true, false)) {
                logger.info("未发现 MCP 客户端，跳过会话保活");
            }
            return;
        }

        if (firstRun.compareAndSet(true, false)) {
            logger.info("MCP 会话保活已启用，纳管 {} 个客户端", clients.size());
        }

        for (McpAsyncClient client : clients) {
            String name = describe(client);
            try {
                client.ping().block(PING_TIMEOUT);
                logger.debug("MCP 会话保活成功: {}", name);
            } catch (Exception e) {
                logger.warn("MCP 会话保活失败 [{}]: {}，尝试重新握手", name, e.getMessage());
                reconnect(client, name);
            }
        }
    }

    private void reconnect(McpAsyncClient client, String name) {
        try {
            client.initialize().block(INITIALIZE_TIMEOUT);
            logger.info("MCP 会话已重建: {}", name);
        } catch (Exception e) {
            logger.error("MCP 会话重建失败 [{}]: {}。日志类工具将不可用，请确认 cls-mcp-server 是否存活，"
                    + "必要时重启后端以重新建立连接", name, e.getMessage());
        }
    }

    private String describe(McpAsyncClient client) {
        try {
            return client.getServerInfo() != null ? client.getServerInfo().name() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
