package hu.zzit.reference.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Integration test for the database example: a real PostgreSQL (Testcontainers) is bootstrapped
 * with the expected role/permission layout (db/init/01-roles.sql — the same script the local
 * compose service uses), Boot's autoconfigured Liquibase migrates through the primary (read-write)
 * pool at startup, and the read-only routing is asserted: {@code readOnly} transactions run on the
 * read-only pool as the read-only user. Runs under failsafe ({@code *IT}).
 *
 * <p>The image tag is pinned to match {@code compose.yaml}.
 */
@SpringBootTest
@ActiveProfiles("db")
@Testcontainers
class ReferencePostgresIT {

    @Container
    @SuppressWarnings("resource") // no leak: the @Testcontainers extension starts/stops @Container fields
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("reference")
            // Same role/permission bootstrap as the compose service (db/init mounted to initdb.d).
            .withCopyFileToContainer(
                    MountableFile.forHostPath("db/init/01-roles.sql"), "/docker-entrypoint-initdb.d/01-roles.sql");

    @DynamicPropertySource
    static void dbProperties(DynamicPropertyRegistry registry) {
        String url =
                POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=reference";
        registry.add("db.readwrite.url", () -> url);
        registry.add("db.readwrite.username", () -> "reference_app_rw_user");
        registry.add("db.readwrite.password", () -> "rw-local");
        registry.add("db.readonly.url", () -> url);
        registry.add("db.readonly.username", () -> "reference_app_ro_user");
        registry.add("db.readonly.password", () -> "ro-local");
    }

    @Autowired
    GreetingRepository greetings;

    @Autowired
    JdbcTemplate jdbc; // over the routing DataSource

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void migratesWritesAndReadsThroughTheSplitPools() {
        String message = "üdvözlet"; // non-ASCII, to prove UTF-8 round-trips

        greetings.save(message); // @Transactional → read-write pool
        assertEquals(message, greetings.findAll().getLast()); // @Transactional(readOnly) → read-only pool
    }

    @Test
    void routesByTransactionReadOnlyFlag() {
        TransactionTemplate readWrite = new TransactionTemplate(transactionManager);
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        assertEquals(
                "reference_app_rw_user",
                readWrite.execute(status -> jdbc.queryForObject("SELECT current_user", String.class)));
        assertEquals(
                "reference_app_ro_user",
                readOnly.execute(status -> jdbc.queryForObject("SELECT current_user", String.class)));
    }

    @Test
    void readOnlyTransactionsCannotWrite() {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        assertThrows(
                DataAccessException.class,
                () -> readOnly.executeWithoutResult(
                        status -> jdbc.update("INSERT INTO greeting (message) VALUES (?)", "nope")));
    }
}
