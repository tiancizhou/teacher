package com.teacher.web.repository;

import com.teacher.web.entity.SingleAnalysisEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface SingleAnalysisRepository extends CrudRepository<SingleAnalysisEntity, Long> {

    Optional<SingleAnalysisEntity> findByTaskId(String taskId);
}
