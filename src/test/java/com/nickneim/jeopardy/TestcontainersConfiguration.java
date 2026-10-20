package com.nickneim.jeopardy;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;

/**
 * The database for tests that start Spring: a throwaway Postgres in Docker,
 * which {@code @ServiceConnection} points the datasource at, and Flyway
 * migrates when the context starts. Import it with
 * {@code @Import(TestcontainersConfiguration.class)}. Docker must be running.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // One container for the whole test run. Test classes with different
    // profiles or properties get different Spring contexts, and each would
    // otherwise start its own. Testcontainers removes it when the JVM exits.
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(image());

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    // The same image as compose.yaml (docker/postgres/Dockerfile), which adds
    // the legacy time zone names Java on Windows sends. Docker caches the
    // build, so only the first run builds it.
    private static DockerImageName image() {
        String name = new ImageFromDockerfile("jeopardy-postgres-test", false)
                .withFileFromPath(".", Path.of("docker/postgres"))
                .get();
        return DockerImageName.parse(name).asCompatibleSubstituteFor("postgres");
    }
}
