package com.wordtrail.user.controller;

import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.common.response.ApiResponse;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.security.SmsVerificationService;
import com.wordtrail.user.service.UserService;
import com.wordtrail.user.vo.TokenVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.CodePointLength;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/phone")
public class PhoneAuthController {
    private final SmsVerificationService sms; private final UserService users; private final CurrentUser current;
    public PhoneAuthController(SmsVerificationService sms,UserService users,CurrentUser current){this.sms=sms;this.users=users;this.current=current;}
    public record Send(@NotNull @Pattern(regexp="1[3-9][0-9]{9}") String phone,
                       @NotNull @Pattern(regexp="REGISTER|LOGIN") String purpose) {}
    public record ProtectedSend(@NotNull @Pattern(regexp="1[3-9][0-9]{9}") String phone,
                       @NotNull @Pattern(regexp="PASSWORD|CHANGE_OLD|CHANGE_NEW|EMAIL_BIND") String purpose) {}
    public record Register(@NotNull @Pattern(regexp="1[3-9][0-9]{9}") String phone,
            @NotNull @Pattern(regexp="[0-9]{6}") String code,
            @NotNull @CodePointLength(min=8,max=64) String password,
            @CodePointLength(max=100) String deviceName) {}
    public record PasswordLogin(@NotNull @Pattern(regexp="1[3-9][0-9]{9}") String phone,
            @NotNull @CodePointLength(min=8,max=64) String password,@CodePointLength(max=100) String deviceName) {}
    public record CodeLogin(@NotNull @Pattern(regexp="1[3-9][0-9]{9}") String phone,
            @NotNull @Pattern(regexp="[0-9]{6}") String code,@CodePointLength(max=100) String deviceName) {}
    public record PasswordChange(@NotNull @Pattern(regexp="[0-9]{6}") String code,
            @NotNull @CodePointLength(min=8,max=64) String newPassword) {}
    public record PhoneChange(@NotNull @Pattern(regexp="[0-9]{6}") String oldCode,
            @NotNull @Pattern(regexp="1[3-9][0-9]{9}") String newPhone,
            @NotNull @Pattern(regexp="[0-9]{6}") String newCode) {}
    @PostMapping("/send") public ApiResponse<Void> send(@Valid @RequestBody Send req,HttpServletRequest http) {
        sms.send(req.phone(),req.purpose(),null,null,http.getRemoteAddr()); return ApiResponse.ok(null);
    }
    @PostMapping("/send-auth") public ApiResponse<Void> sendAuth(@Valid @RequestBody ProtectedSend req,HttpServletRequest http) {
        String phone=users.currentPhone();
        if((!req.purpose().equals("CHANGE_NEW") && !phone.equals(req.phone())) || (req.purpose().equals("CHANGE_NEW") && phone.equals(req.phone())))
            throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_PHONE","手机号不正确");
        sms.send(req.phone(),req.purpose(),current.id(),current.sessionId(),http.getRemoteAddr()); return ApiResponse.ok(null);
    }
    @PostMapping("/register") public ApiResponse<TokenVO> register(@Valid @RequestBody Register req) {
        sms.verify(req.phone(),"REGISTER",null,null,req.code()); return ApiResponse.ok(users.registerPhone(req.phone(),req.password(),req.deviceName()));
    }
    @PostMapping("/password-login") public ApiResponse<TokenVO> passwordLogin(@Valid @RequestBody PasswordLogin req,HttpServletRequest http) {
        sms.limitPassword(req.phone(),http.getRemoteAddr());
        return ApiResponse.ok(users.passwordPhone(req.phone(),req.password(),req.deviceName()));
    }
    @PostMapping("/code-login") public ApiResponse<TokenVO> codeLogin(@Valid @RequestBody CodeLogin req) {
        sms.verify(req.phone(),"LOGIN",null,null,req.code()); return ApiResponse.ok(users.loginPhone(req.phone(),req.deviceName()));
    }
    @PutMapping("/password") public ApiResponse<Void> password(@Valid @RequestBody PasswordChange req) {
        String phone=users.currentPhone(); sms.verify(phone,"PASSWORD",current.id(),current.sessionId(),req.code());
        users.setPhonePassword(phone,req.newPassword()); return ApiResponse.ok(null);
    }
    @PutMapping("/binding") public ApiResponse<Void> binding(@Valid @RequestBody PhoneChange req) {
        String phone=users.currentPhone();
        if(phone.equals(req.newPhone())) throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_PHONE","新手机号不能与原手机号相同");
        sms.verify(phone,"CHANGE_OLD",current.id(),current.sessionId(),req.oldCode());
        sms.verify(req.newPhone(),"CHANGE_NEW",current.id(),current.sessionId(),req.newCode());
        users.changePhone(phone,req.newPhone()); return ApiResponse.ok(null);
    }
}
