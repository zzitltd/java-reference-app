package hu.zzit.reference.db;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

/**
 * Database example — optional, gated on {@code db.enabled} (default false; Boot's single-DataSource
 * autoconfiguration is excluded in application.yaml).
 *
 * <p><b>Expected database layout</b> (provisioned WITH the database, outside this app — see
 * {@code db/init/01-roles.sql} for the reference layout): NOLOGIN group roles (owner / rw / ro), a
 * schema owned by the owner group, two per-application login users (members of rw and ro), and
 * <b>default privileges</b> that auto-grant everything the rw user creates to the rw/ro groups. The
 * app therefore connects with two users:
 *
 * <ul>
 *   <li><b>read-write</b> — DML plus {@code CREATE} on the schema; Boot's autoconfigured Liquibase
 *       migrates through it (as the primary DataSource), and default privileges make the changelog
 *       GRANT-free.
 *   <li><b>read-only</b> — SELECT only; in a real deployment its URL typically points at a replica.
 * </ul>
 *
 * <p><b>Why a config class:</b> {@code spring.datasource.*} configures exactly ONE DataSource —
 * Boot has no property-driven second pool and no read/write routing. The two pools are defined the
 * Boot-documented way ({@link DataSourceProperties} per pool), with key names mirroring
 * {@code spring.datasource}: {@code db.{readwrite,readonly}.{url,username,password}} +
 * {@code db.*.hikari.*} for pool tuning.
 *
 * <p>Routing between the pools is Spring's own {@link LazyConnectionDataSourceProxy}: its
 * {@code readOnlyDataSource} serves every {@code @Transactional(readOnly = true)} scope, so
 * application code uses ONE {@code JdbcTemplate} and expresses intent through transaction
 * annotations (see {@link GreetingRepository}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "db.enabled", havingValue = "true")
class DatabaseConfiguration {

    @Bean
    @ConfigurationProperties("db.readwrite")
    DataSourceProperties readWriteDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("db.readwrite.hikari")
    HikariDataSource readWriteDataSource(@Qualifier("readWriteDataSourceProperties") DataSourceProperties properties) {
        return properties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @ConfigurationProperties("db.readonly")
    DataSourceProperties readOnlyDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("db.readonly.hikari")
    HikariDataSource readOnlyDataSource(@Qualifier("readOnlyDataSourceProperties") DataSourceProperties properties) {
        return properties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    /** Read-only transactions go to the read-only pool; everything else to the read-write pool. */
    @Bean
    @Primary
    DataSource routingDataSource(
            @Qualifier("readWriteDataSource") DataSource readWrite,
            @Qualifier("readOnlyDataSource") DataSource readOnly) {
        LazyConnectionDataSourceProxy routing = new LazyConnectionDataSourceProxy(readWrite);
        routing.setReadOnlyDataSource(readOnly);
        return routing;
    }

    @Bean
    JdbcTemplate jdbcTemplate(@Qualifier("routingDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
