package com.nfpl.access.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;

/** Loads the sample data ("sample") or the scale case ("scale": 500 users, 200,000 jobs). Wipes people/jobs first. */
@Component
public class SeedLoader {
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final String demoPassword;

    public SeedLoader(DataSource dataSource, JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                      @Value("${app.demo-password}") String demoPassword) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.demoPassword = demoPassword;
    }

    public void load(String mode) {
        boolean scale = "scale".equalsIgnoreCase(mode);
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setSqlScriptEncoding("UTF-8");
        populator.addScript(new ClassPathResource("db/seed/00_reset_and_people.sql"));
        if (scale) {
            populator.addScript(new ClassPathResource("db/seed/05_set_scale.sql"));
            populator.addScript(new ClassPathResource("db/seed/20_scale_people.sql"));
        } else {
            populator.addScript(new ClassPathResource("db/seed/05_set_sample.sql"));
        }
        populator.addScript(new ClassPathResource("db/seed/10_jobs.sql"));
        populator.execute(dataSource);   // one connection for all scripts, so the @variables carry over
        ensureDemoPasswords();
    }

    public void ensureDemoPasswords() {
        jdbc.update("UPDATE app_user SET password_hash = ? WHERE password_hash IS NULL",
                passwordEncoder.encode(demoPassword));
    }
}
