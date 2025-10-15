package com.achen.shelf.cli;

import com.achen.shelf.config.AppConfig;
import com.achen.shelf.db.Migrations;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/**
 * {@code shelf migrate} — applies the Flyway migrations.
 *
 * <p>The same migrations {@code ./gradlew flywayMigrate} runs, reachable from inside the container
 * images M2 introduces, where there is no Gradle.
 */
@CommandLine.Command(
    name = "migrate",
    mixinStandardHelpOptions = true,
    description = "Apply database migrations.")
public final class MigrateCommand implements Callable<Integer> {

  @Override
  public Integer call() {
    AppConfig app = AppConfig.fromEnv();
    int applied = Migrations.migrate(app.dbUrl(), app.dbUser(), app.dbPassword());
    System.out.printf("applied %d migration(s) to %s%n", applied, app.dbUrl());
    return CommandLine.ExitCode.OK;
  }
}
