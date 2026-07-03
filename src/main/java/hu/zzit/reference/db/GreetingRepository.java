package hu.zzit.reference.db;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Minimal repository over the Liquibase-migrated {@code greeting} table. One {@code JdbcTemplate};
 * the pool split is expressed through transaction semantics: {@code @Transactional(readOnly=true)}
 * work is routed to the read-only pool (typically a replica) by the routing DataSource — see
 * {@link DatabaseConfiguration}.
 */
@Repository
@ConditionalOnProperty(name = "db.enabled", havingValue = "true")
public class GreetingRepository {

    private final JdbcTemplate jdbc;

    GreetingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbcTemplate;
    }

    @Transactional
    public void save(String message) {
        jdbc.update("INSERT INTO greeting (message) VALUES (?)", message);
    }

    @Transactional(readOnly = true)
    public List<String> findAll() {
        return jdbc.queryForList("SELECT message FROM greeting ORDER BY id", String.class);
    }
}
