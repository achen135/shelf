package com.achen.shelf.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Applies the Flyway migrations from inside the application.
 *
 * <p>`./gradlew flywayMigrate` covers the developer loop, but the containers M2 introduces have no
 * Gradle, and tests need to migrate a throwaway database before every run. Both call this.
 */
public final class Migrations {

  private Migrations() {}

  /** Migrates to the latest version, returning how many migrations were applied. */
  public static int migrate(String jdbcUrl, String user, String password) {
    Flyway flyway =
        Flyway.configure()
            .dataSource(jdbcUrl, user, password)
            .locations("classpath:db/migration")
            .load();
    MigrateResult result = flyway.migrate();
    return result.migrationsExecuted;
  }
}
