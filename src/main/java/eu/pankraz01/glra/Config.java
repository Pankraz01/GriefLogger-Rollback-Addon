package eu.pankraz01.glra;

import net.neoforged.neoforge.common.ModConfigSpec;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // Database settings for rollback addon
    public static final ModConfigSpec.ConfigValue<String> DB_HOST = BUILDER
            .comment("MariaDB host for rollback storage")
            .define("dbHost", "localhost");

    public static final ModConfigSpec.IntValue DB_PORT = BUILDER
            .comment("MariaDB port")
            .defineInRange("dbPort", 3306, 1, 65535);

    public static final ModConfigSpec.ConfigValue<String> DB_NAME = BUILDER
            .comment("MariaDB database name")
            .define("dbName", "grieflogger");

    public static final ModConfigSpec.ConfigValue<String> DB_USER = BUILDER
            .comment("MariaDB user")
            .define("dbUser", "root");

    public static final ModConfigSpec.ConfigValue<String> DB_PASSWORD = BUILDER
            .comment("MariaDB password")
            .define("dbPassword", "");

    // Rollback processing tuning
    public static final ModConfigSpec.IntValue ROLLBACK_BATCH_SIZE = BUILDER
            .comment("Number of rollback actions processed per server tick")
            .defineInRange("rollbackBatchSize", 200, 1, 10000);

    public static final ModConfigSpec.IntValue PROGRESS_TICK_INTERVAL = BUILDER
            .comment("How many ticks between progress logs during a running rollback")
            .defineInRange("progressTickInterval", 20, 1, 1200);

    static final ModConfigSpec SPEC = BUILDER.build();
}
