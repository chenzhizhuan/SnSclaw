package vip.mate.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway auto-repair configuration.
 * <p>
 * Replaces the default {@link FlywayMigrationInitializer} with one that
 * calls {@code flyway.repair()} before {@code flyway.migrate()}.
 * This handles failed migrations and checksum mismatches transparently
 * during version upgrades — especially important for Desktop app users
 * who cannot manually run CLI commands.
 *
 * @author SnSclaw Team
 */
@Slf4j
@Configuration
public class FlywayRepairConfig {

    @Bean
    public FlywayMigrationInitializer flywayInitializer(Flyway flyway) {
        return new FlywayMigrationInitializer(flyway, f -> {
            log.info("[Flyway] Running repair before migrate (auto-fix failed/changed migrations)...");
            f.repair();
            f.migrate();
        });
    }
}
