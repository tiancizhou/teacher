package com.teacher.ai.provider;

import com.teacher.ai.config.AiProperties;
import com.teacher.common.exception.AiServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 提供商工厂，根据配置选择对应的 Provider。
 */
@Component
@RequiredArgsConstructor
public class AiProviderFactory {

    private final List<AiProvider> providers;
    private final AiProperties properties;

    /**
     * 获取当前配置的 AI 提供商。
     */
    public AiProvider getProvider() {
        String target = properties.getProvider();
        return providers.stream()
                .filter(p -> p.getProviderName().equalsIgnoreCase(target))
                .findFirst()
                .orElseThrow(() -> new AiServiceException(
                        "未找到 AI 提供商: " + target + "，可选: openai, anthropic"));
    }

    /**
     * 根据名称获取指定的提供商。
     */
    public AiProvider getProvider(String providerName) {
        return providers.stream()
                .filter(p -> p.getProviderName().equalsIgnoreCase(providerName))
                .findFirst()
                .orElseThrow(() -> new AiServiceException("未找到 AI 提供商: " + providerName));
    }
}
