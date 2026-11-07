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
import com.wordtrail.common.response.PageVO;
import com.wordtrail.learning.dto.WordHistoryQuery;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final UserService users;
    private final CurrentUser currentUser;

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
    @GetMapping("/sessions") public ApiResponse<PageVO<AuthSessionVO>> sessions(@Valid @ModelAttribute WordHistoryQuery query){return ApiResponse.ok(users.sessions(query));}
    @PostMapping("/sessions/{sessionId}/revoke")
    public ApiResponse<Void> revoke(@PathVariable @Pattern(regexp="[a-f0-9]{32}") String sessionId){users.revokeSession(sessionId);return ApiResponse.ok(null);}
    @PostMapping("/logout-others") public ApiResponse<TokenVO> logoutOthers(@Valid @RequestBody LogoutOthersRequest request){return ApiResponse.ok(users.logoutOthers(request));}
}
