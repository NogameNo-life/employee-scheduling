package org.acme.employeescheduling.config;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;

import com.zaxxer.hikari.HikariDataSource;

import org.acme.employeescheduling.entity.plr.PlrBr;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Main database (MES): PLR_* tables. Entities in {@code entity.plr}, repositories in {@code repository.plr}.
 * Both databases get the spring.jpa.properties.* settings through {@link EntityManagerFactoryBuilder}.
 */
@Configuration
@EnableJpaRepositories(basePackages = "org.acme.employeescheduling.repository.plr",
        entityManagerFactoryRef = "mesEntityManagerFactory", transactionManagerRef = "mesTransactionManager")
public class MesDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("app.datasource.mes")
    public HikariDataSource mesDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    public LocalContainerEntityManagerFactoryBean mesEntityManagerFactory(EntityManagerFactoryBuilder builder,
            @Qualifier("mesDataSource") DataSource dataSource) {
        return builder.dataSource(dataSource).packages(PlrBr.class).persistenceUnit("mes").build();
    }

    @Bean
    @Primary
    public PlatformTransactionManager mesTransactionManager(
            @Qualifier("mesEntityManagerFactory") EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
