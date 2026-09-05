package org.example.monitor;

import com.tencentcloudapi.cls.v20201016.ClsClient;
import com.tencentcloudapi.cls.v20201016.models.DescribeTopicsRequest;
import com.tencentcloudapi.cls.v20201016.models.DescribeTopicsResponse;
import com.tencentcloudapi.cls.v20201016.models.Filter;
import com.tencentcloudapi.cls.v20201016.models.TopicInfo;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import lombok.Builder;
import lombok.Value;
import org.example.config.ClsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ClsTopicVerifier {

    private static final Logger logger = LoggerFactory.getLogger(ClsTopicVerifier.class);

    private final ClsProperties clsProperties;

    public ClsTopicVerifier(ClsProperties clsProperties) {
        this.clsProperties = clsProperties;
    }

    public ClsTopicVerifyResult verifyConfiguredTopic() {
        if (!clsProperties.hasCredentials()) {
            return ClsTopicVerifyResult.builder()
                    .success(false)
                    .message("CLS 密钥未配置")
                    .build();
        }

        String region = clsProperties.getRegion();
        String configuredTopicId = clsProperties.getTopicId();
        String logsetId = clsProperties.getLogsetId();

        try {
            ClsClient client = createClient(region);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("region", region);
            details.put("configuredTopicId", configuredTopicId);
            details.put("logsetId", logsetId);

            TopicInfo matchedTopic = findTopicById(client, configuredTopicId);
            if (matchedTopic != null) {
                return ClsTopicVerifyResult.builder()
                        .success(true)
                        .message("Topic 存在且可访问")
                        .topicId(matchedTopic.getTopicId())
                        .topicName(matchedTopic.getTopicName())
                        .logsetId(matchedTopic.getLogsetId())
                        .region(region)
                        .details(details)
                        .build();
            }

            List<TopicInfo> topicsInLogset = listTopicsByLogset(client, logsetId);
            details.put("topicsInLogset", summarizeTopics(topicsInLogset));

            String hint = buildHint(configuredTopicId, logsetId, topicsInLogset);
            logger.warn("CLS Topic 校验失败: {}", hint);

            return ClsTopicVerifyResult.builder()
                    .success(false)
                    .message("Topic 在当前地域不存在，或 TopicId/地域/密钥账号不匹配")
                    .region(region)
                    .details(details)
                    .hint(hint)
                    .build();
        } catch (Exception e) {
            logger.error("CLS Topic 校验异常: {}", e.getMessage());
            return ClsTopicVerifyResult.builder()
                    .success(false)
                    .message("CLS API 调用失败: " + e.getMessage())
                    .region(region)
                    .build();
        }
    }

    private TopicInfo findTopicById(ClsClient client, String topicId) throws Exception {
        if (isBlank(topicId)) {
            return null;
        }
        Filter filter = new Filter();
        filter.setKey("topicId");
        filter.setValues(new String[]{topicId});

        DescribeTopicsRequest request = new DescribeTopicsRequest();
        request.setFilters(new Filter[]{filter});
        request.setLimit(1L);

        DescribeTopicsResponse response = client.DescribeTopics(request);
        if (response.getTopics() != null && response.getTopics().length > 0) {
            return response.getTopics()[0];
        }
        return null;
    }

    private List<TopicInfo> listTopicsByLogset(ClsClient client, String logsetId) throws Exception {
        if (isBlank(logsetId)) {
            return List.of();
        }
        Filter filter = new Filter();
        filter.setKey("logsetId");
        filter.setValues(new String[]{logsetId});

        DescribeTopicsRequest request = new DescribeTopicsRequest();
        request.setFilters(new Filter[]{filter});
        request.setLimit(20L);

        DescribeTopicsResponse response = client.DescribeTopics(request);
        if (response.getTopics() == null) {
            return List.of();
        }
        return List.of(response.getTopics());
    }

    private List<Map<String, String>> summarizeTopics(List<TopicInfo> topics) {
        List<Map<String, String>> result = new ArrayList<>();
        for (TopicInfo topic : topics) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("topicId", topic.getTopicId());
            item.put("topicName", topic.getTopicName());
            item.put("logsetId", topic.getLogsetId());
            result.add(item);
        }
        return result;
    }

    private String buildHint(String configuredTopicId, String logsetId, List<TopicInfo> topicsInLogset) {
        if (!topicsInLogset.isEmpty()) {
            TopicInfo first = topicsInLogset.get(0);
            return String.format(
                    "当前配置的 TopicId=%s 在地域 %s 下不存在。日志集 %s 下实际 Topic 为：%s（名称：%s）。请把 cls.env 里的 CLS_TOPIC_ID 改成这个值。",
                    configuredTopicId,
                    clsProperties.getRegion(),
                    logsetId,
                    first.getTopicId(),
                    first.getTopicName());
        }
        return String.format(
                "当前配置的 TopicId=%s 在地域 %s 下不存在。请到 CLS 控制台确认：1) 地域是否为广州(ap-guangzhou)；2) TopicId 是否从「日志主题详情页」复制；3) 密钥是否与创建 Topic 的账号一致。",
                configuredTopicId,
                clsProperties.getRegion());
    }

    private ClsClient createClient(String region) {
        Credential credential = new Credential(clsProperties.getSecretId(), clsProperties.getSecretKey());
        HttpProfile httpProfile = new HttpProfile();
        httpProfile.setEndpoint("cls.tencentcloudapi.com");
        ClientProfile clientProfile = new ClientProfile();
        clientProfile.setHttpProfile(httpProfile);
        return new ClsClient(credential, region, clientProfile);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Value
    @Builder
    public static class ClsTopicVerifyResult {
        boolean success;
        String message;
        String topicId;
        String topicName;
        String logsetId;
        String region;
        String hint;
        Map<String, Object> details;
    }
}
