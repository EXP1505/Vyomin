package com.vyomin.core_api.controller;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.vyomin.core_api.dto.AuthResponse;
import com.vyomin.core_api.model.User;
import com.vyomin.core_api.repository.UserRepository;
import com.vyomin.core_api.security.JwtUtil;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.GeneralSecurityException;
import java.util.Collections;
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

    @Value("${google.oauth.client-id:}")
    private String googleClientId;

    private GoogleIdTokenVerifier googleIdTokenVerifier;

    @PostConstruct
    private void init() {
        if (googleClientId != null && !googleClientId.isBlank()) {
            googleIdTokenVerifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                    .setAudience(Collections.singletonList(googleClientId))
                    .build();
        }
    }

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
        user.setProvider(User.AuthProvider.LOCAL);

        userRepository.save(user);

        String token = jwtUtil.generateToken(email);
        return ResponseEntity.ok(new AuthResponse(token, user.getEmail(), user.getName(), user.getPictureUrl()));
    }

    // This endpoint allows users to log in by providing their email and password. It verifies the credentials and returns a JWT token if the login is successful.
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        String password = request.get("password");

        Optional<User> userOptional = userRepository.findByEmail(email);

        if (userOptional.isPresent()) {
            User user = userOptional.get();
            if (user.getPassword() != null && passwordEncoder.matches(password, user.getPassword())) {
                String token = jwtUtil.generateToken(email);
                return ResponseEntity.ok(new AuthResponse(token, user.getEmail(), user.getName(), user.getPictureUrl()));
            }
        }

        return ResponseEntity.status(401).body("Invalid credentials");
    }

    // Verifies a Google ID token from the frontend (Google Identity Services), then finds or creates
    // a local user for that Google account and issues our own JWT for subsequent API calls.
    @PostMapping("/google")
    public ResponseEntity<?> googleLogin(@RequestBody Map<String, String> request) {
        if (googleIdTokenVerifier == null) {
            return ResponseEntity.status(503).body("Google sign-in is not configured on this server");
        }

        String idTokenString = request.get("idToken");
        if (idTokenString == null || idTokenString.isBlank()) {
            return ResponseEntity.badRequest().body("Missing idToken");
        }

        GoogleIdToken idToken;
        try {
            idToken = googleIdTokenVerifier.verify(idTokenString);
        } catch (GeneralSecurityException | java.io.IOException | IllegalArgumentException e) {
            return ResponseEntity.status(401).body("Could not verify Google token");
        }

        if (idToken == null) {
            return ResponseEntity.status(401).body("Invalid Google token");
        }

        GoogleIdToken.Payload payload = idToken.getPayload();
        String email = payload.getEmail();
        String name = (String) payload.get("name");
        String picture = (String) payload.get("picture");
        String googleSub = payload.getSubject();

        User user = userRepository.findByEmail(email).orElseGet(User::new);
        user.setEmail(email);
        user.setProvider(User.AuthProvider.GOOGLE);
        user.setProviderId(googleSub);
        if (name != null) user.setName(name);
        if (picture != null) user.setPictureUrl(picture);
        userRepository.save(user);

        String token = jwtUtil.generateToken(email);
        return ResponseEntity.ok(new AuthResponse(token, user.getEmail(), user.getName(), user.getPictureUrl()));
    }
}
