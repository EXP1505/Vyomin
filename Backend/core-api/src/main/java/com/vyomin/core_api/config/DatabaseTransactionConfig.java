package com.vyomin.core_api.config;

import jakarta.persistence.EntityManagerFactory;
import org.neo4j.driver.Driver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.neo4j.core.DatabaseSelectionProvider;
import org.springframework.data.neo4j.core.transaction.Neo4jTransactionManager;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Both spring-boot-starter-data-jpa (Postgres) and spring-boot-starter-data-neo4j (AuraDB) are
 * on the classpath. Boot's Neo4j auto-configuration (DataNeo4jAutoConfiguration) registers its
 * Neo4jTransactionManager as a bean named "transactionManager", guarded by
 * @ConditionalOnMissingBean(TransactionManager.class) - and JPA's JpaTransactionManager (also
 * conventionally "transactionManager") wins that slot, so the Neo4j bean method never fires.
 * Every Neo4j repository call then runs with no Neo4j-specific PlatformTransactionManager bound,
 * and Neo4jTemplate$DefaultExecutableQuery NPEs deep inside SDN internals trying to use it.
 * See https://github.com/spring-projects/spring-data-neo4j/issues/2931.
 *
 * Fix: give each store its own explicitly named transaction manager, and point Neo4j
 * repositories at theirs via @EnableNeo4jRepositories(transactionManagerRef = ...). Declaring
 * @EnableNeo4jRepositories here makes Boot's own Neo4j repository auto-configuration back off
 * for these packages, same as @EnableJpaRepositories would for JPA.
 */
@Configuration
@EnableNeo4jRepositories(
        basePackages = {
                "com.vyomin.core_api.repository.graph",
                "com.vyomin.core_api.repository.intelligencegraph"
        },
        transactionManagerRef = "neo4jTransactionManager"
)
public class DatabaseTransactionConfig {

    @Bean(name = "transactionManager")
    @Primary
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    @Bean(name = "neo4jTransactionManager")
    public PlatformTransactionManager neo4jTransactionManager(Driver driver,
                                                                DatabaseSelectionProvider databaseSelectionProvider) {
        return new Neo4jTransactionManager(driver, databaseSelectionProvider);
    }
}
