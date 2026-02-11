package com.teacher.web.config;

import org.springframework.data.relational.core.dialect.AbstractDialect;
import org.springframework.data.relational.core.dialect.LimitClause;
import org.springframework.data.relational.core.dialect.LockClause;
import org.springframework.data.relational.core.sql.LockOptions;

/**
 * SQLite 方言 —— Spring Data JDBC 内置不支持 SQLite，手动提供。
 * SQLite 的 SQL 语法和 H2 几乎一致，只需声明 LIMIT/OFFSET 风格即可。
 */
public class SqliteDialect extends AbstractDialect {

    public static final SqliteDialect INSTANCE = new SqliteDialect();

    @Override
    public LimitClause limit() {
        return new LimitClause() {
            @Override
            public String getLimit(long limit) {
                return "LIMIT " + limit;
            }

            @Override
            public String getOffset(long offset) {
                return "OFFSET " + offset;
            }

            @Override
            public String getLimitOffset(long limit, long offset) {
                return "LIMIT " + limit + " OFFSET " + offset;
            }

            @Override
            public Position getClausePosition() {
                return Position.AFTER_ORDER_BY;
            }
        };
    }

    @Override
    public LockClause lock() {
        // SQLite 不支持 SELECT ... FOR UPDATE，返回空实现
        return new LockClause() {
            @Override
            public String getLock(LockOptions lockOptions) {
                return "";
            }

            @Override
            public Position getClausePosition() {
                return Position.AFTER_ORDER_BY;
            }
        };
    }
}
