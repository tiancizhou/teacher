package com.teacher.web.repository;

import com.teacher.web.entity.KeyLogEntity;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface KeyLogRepository extends CrudRepository<KeyLogEntity, Long> {

    /** 某用户在某时间之后的调用次数（防刷） */
    @Query("SELECT COUNT(*) FROM t_key_log WHERE user_id = :userId AND created_at > :since")
    long countRecentCalls(Long userId, String since);

    /** 最近的调用日志 */
    @Query("SELECT * FROM t_key_log ORDER BY created_at DESC LIMIT 50")
    List<KeyLogEntity> findRecent();
}
