package com.teacher.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teacher.common.dto.BatchResult;
import com.teacher.common.dto.CharAnalysis;
import com.teacher.common.dto.SingleCharResult;
import com.teacher.web.entity.AnalysisEntity;
import com.teacher.web.entity.HomeworkEntity;
import com.teacher.web.entity.KeyLogEntity;
import com.teacher.web.entity.SingleAnalysisEntity;
import com.teacher.web.repository.AnalysisRepository;
import com.teacher.web.repository.HomeworkRepository;
import com.teacher.web.repository.KeyLogRepository;
import com.teacher.web.repository.SingleAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 作业数据持久化服务 —— 串联 AI 批改结果与数据库。
 * 基于 Spring Data JDBC，无 Hibernate。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HomeworkDataService {

    private final HomeworkRepository homeworkRepo;
    private final AnalysisRepository analysisRepo;
    private final KeyLogRepository keyLogRepo;
    private final SingleAnalysisRepository singleAnalysisRepo;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter SQLITE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ==================== 持久化 ====================

    /**
     * 保存完整的批改结果到数据库。
     */
    @Transactional
    public HomeworkEntity saveResult(BatchResult result, String originalFileName,
                                     Long userId, String copyBookId) {
        // 1. 保存作业记录
        HomeworkEntity homework = HomeworkEntity.builder()
                .taskId(result.getTaskId())
                .userId(userId)
                .originalFileName(originalFileName)
                .copyBookId(copyBookId)
                .charCount(result.getTotalCharacters())
                .avgScore(result.getAvgOverallScore())
                .status("COMPLETED")
                .processingTimeMs(result.getProcessingTimeMs())
                .build();
        homework = homeworkRepo.save(homework);

        // 2. 逐字保存分析结果
        if (result.getAnalyses() != null) {
            for (CharAnalysis analysis : result.getAnalyses()) {
                String resultJson = toJson(analysis);
                String cacheKey = buildCacheKey(copyBookId, analysis.getRecognizedChar());

                AnalysisEntity entity = AnalysisEntity.builder()
                        .homeworkId(homework.getId())
                        .charIndex(analysis.getCharIndex())
                        .recognizedChar(analysis.getRecognizedChar())
                        .structureScore(analysis.getStructureScore())
                        .strokeScore(analysis.getStrokeScore())
                        .overallScore(analysis.getOverallScore())
                        .resultJson(resultJson)
                        .overallComment(analysis.getOverallComment())
                        .suggestion(analysis.getSuggestion())
                        .cacheKey(cacheKey)
                        .build();
                analysisRepo.save(entity);
            }
        }

        log.info("批改结果已持久化: taskId={}, homeworkId={}, 字数={}",
                result.getTaskId(), homework.getId(), result.getTotalCharacters());
        return homework;
    }

    /**
     * 保存单字精批结果到数据库。
     */
    public SingleAnalysisEntity saveSingleResult(SingleCharResult result, Long userId) {
        String createdAt = result.getCreatedAt() != null && !result.getCreatedAt().isEmpty()
                ? result.getCreatedAt()
                : LocalDateTime.now().format(SQLITE_FMT);
        SingleAnalysisEntity entity = SingleAnalysisEntity.builder()
                .taskId(result.getTaskId())
                .userId(userId)
                .recognizedChar(result.getRecognizedChar())
                .structureScore(result.getStructureScore())
                .structureDetail(result.getStructureDetail())
                .strokeScore(result.getStrokeScore())
                .strokeDetail(result.getStrokeDetail())
                .balanceScore(result.getBalanceScore())
                .balanceDetail(result.getBalanceDetail())
                .spacingScore(result.getSpacingScore())
                .spacingDetail(result.getSpacingDetail())
                .overallScore(result.getOverallScore())
                .overallComment(result.getOverallComment())
                .suggestion(result.getSuggestion())
                .processingTimeMs(result.getProcessingTimeMs())
                .createdAt(createdAt)
                .build();
        entity = singleAnalysisRepo.save(entity);

        log.info("单字精批结果已持久化: taskId={}, char={}, score={}",
                result.getTaskId(), result.getRecognizedChar(), result.getOverallScore());
        return entity;
    }

    // ==================== 查询 ====================

    /**
     * 通过 taskId 查询批改结果。
     */
    public Optional<BatchResult> findByTaskId(String taskId) {
        return homeworkRepo.findByTaskId(taskId).map(hw -> {
            List<AnalysisEntity> entities = analysisRepo.findByHomeworkId(hw.getId());
            List<CharAnalysis> analyses = entities.stream()
                    .map(this::toCharAnalysis)
                    .toList();

            BatchResult result = BatchResult.builder()
                    .taskId(hw.getTaskId())
                    .imageId(hw.getTaskId())
                    .totalCharacters(hw.getCharCount() != null ? hw.getCharCount() : analyses.size())
                    .analyses(analyses)
                    .avgOverallScore(hw.getAvgScore() != null ? hw.getAvgScore() : 0)
                    .processingTimeMs(hw.getProcessingTimeMs() != null ? hw.getProcessingTimeMs() : 0)
                    .createdAt(hw.getCreatedAt())
                    .build();
            result.computeSummary();
            return result;
        });
    }

    /**
     * 查询用户最近的作业列表。
     */
    public List<HomeworkEntity> findRecentHomeworks(Long userId) {
        return homeworkRepo.findRecentByUserId(userId);
    }

    // ==================== 缓存命中 ====================

    /**
     * 尝试从历史分析中命中缓存。
     * 同一字帖中同一个字有高分点评（>=80）则直接复用。
     */
    public Optional<CharAnalysis> tryCacheHit(String copyBookId, String recognizedChar) {
        if (copyBookId == null || recognizedChar == null) {
            return Optional.empty();
        }
        String cacheKey = buildCacheKey(copyBookId, recognizedChar);
        return analysisRepo.findCacheHit(cacheKey, 80)
                .map(this::toCharAnalysis);
    }

    // ==================== 成长曲线 ====================

    /**
     * 某用户某个字的历史评分趋势。
     */
    public List<CharAnalysis> getGrowthCurve(Long userId, String charName) {
        return analysisRepo.findGrowthCurve(userId, charName).stream()
                .map(this::toCharAnalysis)
                .toList();
    }

    // ==================== Key 调用日志 ====================

    /**
     * 记录一次 API 调用日志。
     */
    public void logKeyUsage(String taskId, Long userId, String provider, String model,
                            int charCount, long latencyMs, boolean success,
                            String errorMessage, int cacheHits) {
        KeyLogEntity logEntity = KeyLogEntity.builder()
                .taskId(taskId)
                .userId(userId)
                .provider(provider)
                .model(model)
                .charCount(charCount)
                .latencyMs(latencyMs)
                .success(success)
                .errorMessage(errorMessage)
                .cacheHits(cacheHits)
                .build();
        keyLogRepo.save(logEntity);
    }

    /**
     * 防刷检查：某用户最近 N 分钟内的调用次数。
     */
    public long countRecentCalls(Long userId, int minutes) {
        if (userId == null) return 0;
        // SQLite 存的是 TEXT 格式时间，直接传格式化字符串比较
        String since = LocalDateTime.now().minusMinutes(minutes).format(SQLITE_FMT);
        return keyLogRepo.countRecentCalls(userId, since);
    }

    // ==================== 内部方法 ====================

    private String buildCacheKey(String copyBookId, String charName) {
        if (copyBookId == null || charName == null) return null;
        return copyBookId + ":" + charName;
    }

    private CharAnalysis toCharAnalysis(AnalysisEntity e) {
        return CharAnalysis.builder()
                .charIndex(e.getCharIndex() != null ? e.getCharIndex() : 0)
                .recognizedChar(e.getRecognizedChar())
                .structureScore(e.getStructureScore() != null ? e.getStructureScore() : 0)
                .strokeScore(e.getStrokeScore() != null ? e.getStrokeScore() : 0)
                .overallScore(e.getOverallScore() != null ? e.getOverallScore() : 0)
                .overallComment(e.getOverallComment())
                .suggestion(e.getSuggestion())
                .build();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("JSON 序列化失败", e);
            return "{}";
        }
    }
}
