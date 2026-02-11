package com.teacher.web.repository;

import com.teacher.web.entity.AnalysisEntity;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface AnalysisRepository extends CrudRepository<AnalysisEntity, Long> {

    @Query("SELECT * FROM t_analysis WHERE homework_id = :homeworkId ORDER BY char_index")
    List<AnalysisEntity> findByHomeworkId(Long homeworkId);

    /**
     * 缓存命中 —— 查找同一字帖同一字的高分历史点评。
     */
    @Query("SELECT * FROM t_analysis WHERE cache_key = :cacheKey AND overall_score >= :minScore ORDER BY created_at DESC LIMIT 1")
    Optional<AnalysisEntity> findCacheHit(String cacheKey, Integer minScore);

    /**
     * 成长曲线 —— 某用户某个字的历史评分。
     */
    @Query("SELECT a.* FROM t_analysis a JOIN t_homework h ON a.homework_id = h.id WHERE h.user_id = :userId AND a.recognized_char = :charName ORDER BY a.created_at ASC")
    List<AnalysisEntity> findGrowthCurve(Long userId, String charName);
}
