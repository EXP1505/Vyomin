package com.vyomin.core_api.controller;

import com.vyomin.core_api.model.User;
import com.vyomin.core_api.repository.UserRepository;
import com.vyomin.core_api.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
// This controller handles authentication-related endpoints such as user registration and login
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @PostMapping("/signup")
    // This endpoint allows users to register by providing an email and password. It checks if the email is already taken, encodes the password, and saves the new user to the database.
    public ResponseEntity<?> signup(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        String password = request.get("password");

        if (userRepository.findByEmail(email).isPresent()) {
            return ResponseEntity.badRequest().body("Email is already taken!");
        }

        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));

        userRepository.save(user);

        return ResponseEntity.ok("User registered successfully");
    }

    // This endpoint allows users to log in by providing their email and password. It verifies the credentials and returns a JWT token if the login is successful.
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        String password = request.get("password");

        Optional<User> userOptional = userRepository.findByEmail(email);

        if (userOptional.isPresent()) {
            User user = userOptional.get();
            if (passwordEncoder.matches(password, user.getPassword())) {
                String token = jwtUtil.generateToken(email);
                return ResponseEntity.ok(Map.of("token", token));
            }
        }

        return ResponseEntity.status(401).body("Invalid credentials");
    }
}
