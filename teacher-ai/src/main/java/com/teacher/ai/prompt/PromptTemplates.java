package com.teacher.ai.prompt;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 书法点评 Prompt 模板集合。
 * <p>
 * 所有提示词从 classpath 下的 {@code prompts/*.md} 文件加载，
 * 修改提示词只需编辑对应 .md 文件并重启，无需改代码。
 *
 * <pre>
 * resources/prompts/
 * ├── whole-page-analysis.md   — 整页批改
 * ├── unified-analysis.md      — 单字综合分析
 * ├── structure-analysis.md    — 结构分析（多 Agent）
 * ├── stroke-analysis.md       — 笔画分析（多 Agent）
 * └── comment-generator.md     — 综合评语（多 Agent，含占位符）
 * </pre>
 */
@Slf4j
@Component
public class PromptTemplates {

    private static final String PROMPT_DIR = "prompts/";

    // ===== 整页批改 =====
    private String wholePageAnalysis;

    // ===== 单字模式 =====
    private String unifiedAnalysis;

    // ===== 多 Agent 模式 =====
    private String structureAnalysis;
    private String strokeAnalysis;
    private String commentGeneratorTemplate;

    @PostConstruct
    void loadPrompts() {
        wholePageAnalysis       = loadPrompt("whole-page-analysis.md");
        unifiedAnalysis         = loadPrompt("unified-analysis.md");
        structureAnalysis       = loadPrompt("structure-analysis.md");
        strokeAnalysis          = loadPrompt("stroke-analysis.md");
        commentGeneratorTemplate = loadPrompt("comment-generator.md");

        log.info("已加载 5 个 Prompt 模板 (来自 classpath:prompts/*.md)");
    }

    // ======================== Getter ========================

    /** 整页批改 Prompt */
    public String getWholePageAnalysis() {
        return wholePageAnalysis;
    }

    /** 单字综合分析 Prompt */
    public String getUnifiedAnalysis() {
        return unifiedAnalysis;
    }

    /** 结构分析 Prompt（多 Agent 模式） */
    public String getStructureAnalysis() {
        return structureAnalysis;
    }

    /** 笔画分析 Prompt（多 Agent 模式） */
    public String getStrokeAnalysis() {
        return strokeAnalysis;
    }

    /**
     * 综合评语 Prompt（多 Agent 模式）。
     * 自动填充结构分析和笔画分析结果到占位符。
     *
     * @param structureResult 结构分析的 AI 响应
     * @param strokeResult    笔画分析的 AI 响应
     * @return 填充后的 Prompt
     */
    public String getCommentGenerator(String structureResult, String strokeResult) {
        return commentGeneratorTemplate
                .replace("{structureAnalysis}", structureResult)
                .replace("{strokeAnalysis}", strokeResult);
    }

    // ======================== 加载工具 ========================

    private String loadPrompt(String filename) {
        try {
            ClassPathResource resource = new ClassPathResource(PROMPT_DIR + filename);
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            log.debug("加载 Prompt: {} ({} 字符)", filename, content.length());
            return content;
        } catch (IOException e) {
            log.error("加载 Prompt 失败: {}", filename, e);
            throw new IllegalStateException("无法加载 Prompt 文件: " + PROMPT_DIR + filename, e);
        }
    }
}
