package org.springblade.aiworkflow.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springblade.aiworkflow.config.AiWorkflowProperties;
import org.springblade.aiworkflow.service.IPartACallbackService;
import org.springblade.aiworkflow.vo.StatusUpdateRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * Part A 回调服务。
 *
 * <p>使用专门的短超时 HTTP 客户端,4xx 客户端错误不重试,5xx/网络异常指数退避重试 3 次。
 * 启动时校验 callbackUrl 合法(scheme + 非空 host),非法时直接拒绝调用以避免 SSRF。
 */
@Slf4j
@Service
public class PartACallbackServiceImpl implements IPartACallbackService {

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 1000;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String callbackUrl;
    private final boolean callbackUrlValid;

    public PartACallbackServiceImpl(@Qualifier("callbackHttpClient") OkHttpClient httpClient,
                                     ObjectMapper objectMapper,
                                     AiWorkflowProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.callbackUrl = properties.getPartACallbackUrl();
        this.callbackUrlValid = isAllowedCallbackUrl(this.callbackUrl);
        if (!callbackUrlValid) {
            log.warn("Part A 回调 URL 不合法或不在允许的 scheme 范围(http/https): {} — 回调将被禁用", this.callbackUrl);
        } else {
            log.info("Part A 回调 URL 已配置: {}", this.callbackUrl);
        }
    }

    @Override
    public void notifyStatusUpdate(StatusUpdateRequest request) {
        if (!callbackUrlValid) {
            log.warn("跳过回调 — 回调 URL 不合法: receptionId={}", request.getReceptionId());
            return;
        }
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String json = objectMapper.writeValueAsString(request);
                RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
                Request httpRequest = new Request.Builder()
                        .url(callbackUrl)
                        .post(body)
                        .build();

                try (okhttp3.Response response = httpClient.newCall(httpRequest).execute()) {
                    if (response.isSuccessful()) {
                        log.info("回调Part A成功: receptionId={}, status={}",
                                request.getReceptionId(), request.getOverallStatus());
                        return;
                    }
                    if (response.code() >= 500) {
                        log.warn("回调Part A失败(5xx, 第{}次): HTTP {}", attempt, response.code());
                    } else {
                        log.warn("回调Part A失败(客户端错误{}): 不重试", response.code());
                        return;
                    }
                }
            } catch (IOException e) {
                log.warn("回调Part A网络异常(第{}次): {}", attempt, e.getMessage());
            }

            if (attempt < MAX_RETRIES) {
                try {
                    Thread.sleep(INITIAL_BACKOFF_MS * (long) Math.pow(2, attempt - 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.error("回调Part A最终失败(已重试{}次): receptionId={}", MAX_RETRIES, request.getReceptionId());
    }

    /**
     * 复用 ConfigController.isAllowedBaseUrl 的 SSRF 防护(scheme + 非空 host + 拒绝内网/云元数据),
     * 避免回调 URL 被配成内网地址导致 plan 内容/状态泄露给内网服务。
     */
    private static boolean isAllowedCallbackUrl(String url) {
        return org.springblade.aiworkflow.controller.ConfigController.isAllowedBaseUrl(url);
    }
}
