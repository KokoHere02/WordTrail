package com.wordtrail.user.controller;

import com.wordtrail.common.response.ApiResponse;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.user.dto.*;
import com.wordtrail.user.service.UserService;
import com.wordtrail.user.vo.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final UserService users;
    private final CurrentUser currentUser;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserVO>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(users.register(request)));
    }

    @PostMapping("/login")
    public ApiResponse<TokenVO> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(users.login(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenVO> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.ok(users.refresh(request.getRefreshToken()));
    }

    @GetMapping("/me")
    public ApiResponse<UserVO> me() { return ApiResponse.ok(users.me(currentUser.id())); }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        users.logout(request.getRefreshToken());
        return ApiResponse.ok(null);
    }
}
