package org.acme.employeescheduling.config;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;

import com.zaxxer.hikari.HikariDataSource;

import org.acme.employeescheduling.entity.tabu.BdCard;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Second database (TABU2, SQL Server 2012): employees BD_CARD. Entities in {@code entity.tabu},
 * repositories in {@code repository.tabu}. It needs TLS 1.0, see src/main/jvm/legacy-tls.security.
 * If it is not reachable, CrewDataLoader logs a warning and uses personnel numbers instead.
 */
@Configuration
@EnableJpaRepositories(basePackages = "org.acme.employeescheduling.repository.tabu",
        entityManagerFactoryRef = "tabuEntityManagerFactory", transactionManagerRef = "tabuTransactionManager")
public class TabuDataSourceConfig {

    @Bean
    @ConfigurationProperties("app.datasource.tabu")
    public HikariDataSource tabuDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean tabuEntityManagerFactory(EntityManagerFactoryBuilder builder,
            @Qualifier("tabuDataSource") DataSource dataSource) {
        return builder.dataSource(dataSource).packages(BdCard.class).persistenceUnit("tabu").build();
    }

    @Bean
    public PlatformTransactionManager tabuTransactionManager(
            @Qualifier("tabuEntityManagerFactory") EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
