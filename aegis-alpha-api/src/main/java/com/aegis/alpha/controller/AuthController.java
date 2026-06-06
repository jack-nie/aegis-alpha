package com.aegis.alpha.controller;

import com.aegis.alpha.security.SecurityProfile;
import com.aegis.alpha.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/_backend")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        return authService.login(body.get("username"), body.get("password"));
    }

    @GetMapping("/auth/me")
    public ResponseEntity<Map<String, Object>> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        Map<String, Object> me = authService.me(authorization);
        return me == null ? ResponseEntity.status(HttpStatus.UNAUTHORIZED).build() : ResponseEntity.ok(me);
    }

    @GetMapping("/profile")
    public ResponseEntity<SecurityProfile> profile(@RequestHeader(value = "Authorization", required = false) String authorization) {
        SecurityProfile profile = authService.profile(authorization);
        return profile == null ? ResponseEntity.status(HttpStatus.UNAUTHORIZED).build() : ResponseEntity.ok(profile);
    }
}
