package com.nfpl.access.config;

import com.nfpl.access.service.SeedLoader;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Start the app with --app.seed=sample (or scale) to load data on startup. */
@Component
public class SeedRunner implements ApplicationRunner {
    private final SeedLoader loader;
    private final String mode;
    private final JdbcTemplate jdbc;

    public SeedRunner(SeedLoader loader, JdbcTemplate jdbc, @Value("${app.seed:none}") String mode) {
        this.loader = loader;
        this.jdbc = jdbc;
        this.mode = mode;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean seedIfEmpty = mode.endsWith("-if-empty");
        String seedMode = seedIfEmpty ? mode.substring(0, mode.length() - "-if-empty".length()) : mode;
        if ("sample".equalsIgnoreCase(seedMode) || "scale".equalsIgnoreCase(seedMode)) {
            long users = jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Long.class);
            if (!seedIfEmpty || users == 0) {
                System.out.println(">>> Loading " + seedMode + " data ...");
                loader.load(seedMode);
                System.out.println(">>> Data loaded.");
            } else {
                System.out.println(">>> Existing users found; leaving database data unchanged.");
            }
        }
        loader.ensureDemoPasswords();
    }
}
