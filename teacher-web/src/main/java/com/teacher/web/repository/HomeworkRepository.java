package com.teacher.web.repository;

import com.teacher.web.entity.HomeworkEntity;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface HomeworkRepository extends CrudRepository<HomeworkEntity, Long> {

    Optional<HomeworkEntity> findByTaskId(String taskId);

    @Query("SELECT * FROM t_homework WHERE user_id = :userId ORDER BY created_at DESC LIMIT 10")
    List<HomeworkEntity> findRecentByUserId(Long userId);
}
