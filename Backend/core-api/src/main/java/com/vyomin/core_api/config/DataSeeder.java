package com.vyomin.core_api.config;

import com.vyomin.core_api.model.User;
import com.vyomin.core_api.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class DataSeeder implements CommandLineRunner {

    // Inject the UserRepository to interact with the database
    @Autowired
    private UserRepository userRepository;

    // Inject the PasswordEncoder to encode the default admin password
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${app.admin.email}")
    private String defaultAdminEmail;

    @Value("${app.admin.password}")
    private String defaultAdminPassword;

    @Override
    public void run(String... args) throws Exception {
        // Check if the default admin user already exists
        Optional<User> existingAdmin = userRepository.findByEmail(defaultAdminEmail);

        // If the admin user does not exist, create and save it to the database
        if (existingAdmin.isEmpty()) {
            User admin = new User();
            admin.setEmail(defaultAdminEmail);
            admin.setPassword(passwordEncoder.encode(defaultAdminPassword));
            userRepository.save(admin);
            System.out.println("Default admin user seeded from environment variables: " + defaultAdminEmail);
        } else {
            System.out.println("Default admin user already exists.");
        }
    }
}
