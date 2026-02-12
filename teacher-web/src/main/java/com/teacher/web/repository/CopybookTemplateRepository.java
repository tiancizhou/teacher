package com.teacher.web.repository;

import com.teacher.web.entity.CopybookTemplateEntity;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface CopybookTemplateRepository extends CrudRepository<CopybookTemplateEntity, Long> {

    @Query("SELECT * FROM t_copybook_template ORDER BY id")
    List<CopybookTemplateEntity> findAllTemplates();
}
