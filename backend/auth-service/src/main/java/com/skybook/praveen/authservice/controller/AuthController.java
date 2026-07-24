package com.skybook.praveen.authservice.controller;

import com.skybook.praveen.authservice.dto.CurrentUserResponse;
import com.skybook.praveen.authservice.dto.LoginRequest;
import com.skybook.praveen.authservice.dto.RegisterRequest;
import com.skybook.praveen.authservice.security.SessionCookie;
import com.skybook.praveen.authservice.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SessionCookie sessionCookie;

    @GetMapping("/profile")
    public String profile() {
        return "You are authenticated!";
    }

    @PostMapping("/register")
    public ResponseEntity<String> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    /**
     * Sign in.
     *
     * <p>Returns the token in the body <b>and</b> sets an httpOnly session cookie.
     * Both, deliberately: API clients (Postman, the e2e suite, scripts) read the
     * body and cannot use cookies conveniently, while the browser uses the cookie
     * so the token is never exposed to JavaScript. Dropping the body token would
     * have broken every existing non-browser caller for no security gain - they
     * are not the ones at risk from XSS.
     */
    @PostMapping("/login")
    public ResponseEntity<String> login(@Valid @RequestBody LoginRequest request) {
        String token = authService.login(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie.issue(token))
                .body(token);
    }

    /**
     * Sign out by expiring the session cookie.
     *
     * <p>This endpoint EXISTS BECAUSE the cookie is httpOnly: JavaScript cannot
     * see or delete it, so without a server call the browser has no way to end
     * its own session.
     *
     * <p>It does not revoke the token - there is no revocation list (deferred,
     * SECURITY_HARDENING_MODULE.md §14), so a token already captured elsewhere
     * stays valid until it expires. What this guarantees is that the browser
     * stops presenting it, which is what "sign out" means to the user in front
     * of the screen.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookie.expire())
                .build();
    }

    /**
     * Who the caller is, according to their token.
     *
     * <p>Also a consequence of httpOnly: the SPA can no longer decode the token
     * to find out who is signed in, so it asks. Returning the claims the server
     * already validated is better than the browser parsing an unverified token
     * anyway - it cannot drift from what the server believes.
     */
    @GetMapping("/me")
    public CurrentUserResponse me(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        return new CurrentUserResponse(authentication.getName(), roles);
    }
}
