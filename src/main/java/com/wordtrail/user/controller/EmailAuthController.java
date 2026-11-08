package com.wordtrail.user.controller;

import com.wordtrail.common.response.ApiResponse;
import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.security.EmailVerificationService;
import com.wordtrail.security.SmsVerificationService;
import com.wordtrail.user.service.UserService;
import com.wordtrail.user.vo.TokenVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.CodePointLength;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/auth/email")
public class EmailAuthController {
    private final EmailVerificationService email;
    private final UserService users;
    private final CurrentUser current;
    private final SmsVerificationService sms;
    public EmailAuthController(EmailVerificationService email,UserService users,CurrentUser current,SmsVerificationService sms) { this.email=email;this.users=users;this.current=current;this.sms=sms; }
    private static final String ADDRESS="[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,}";
    public record Send(@NotBlank @Size(max=254) @Email @Pattern(regexp=ADDRESS) String email,@NotNull @Pattern(regexp="REGISTER|LOGIN") String purpose) {}
    public record BindSend(@NotBlank @Size(max=254) @Email @Pattern(regexp=ADDRESS) String email) {}
    public record Register(@NotBlank @Size(max=254) @Email @Pattern(regexp=ADDRESS) String email,@NotNull @Pattern(regexp="[0-9]{6}") String code,@NotNull @CodePointLength(min=8,max=64) String password,@CodePointLength(max=100) String deviceName) {}
    public record CodeLogin(@NotBlank @Size(max=254) @Email @Pattern(regexp=ADDRESS) String email,@NotNull @Pattern(regexp="[0-9]{6}") String code,@CodePointLength(max=100) String deviceName) {}
    public record PasswordLogin(@NotBlank @Size(max=254) @Email @Pattern(regexp=ADDRESS) String email,@NotNull @CodePointLength(min=8,max=64) String password,@CodePointLength(max=100) String deviceName) {}
    public record Bind(@NotBlank @Size(max=254) @Email @Pattern(regexp=ADDRESS) String email,@NotNull @Pattern(regexp="[0-9]{6}") String code,@Pattern(regexp="[0-9]{6}") String phoneCode,@CodePointLength(min=8,max=64) String password) {}
    public record PasswordChange(@NotNull @Pattern(regexp="[0-9]{6}") String code,@NotNull @CodePointLength(min=8,max=64) String newPassword) {}
    public record ChangeSend(@Size(max=254) @Email @Pattern(regexp=ADDRESS) String newEmail,@NotNull @Pattern(regexp="CHANGE_OLD|CHANGE_NEW") String purpose) {}
    public record Change(@NotBlank @Size(max=254) @Email @Pattern(regexp=ADDRESS) String newEmail,@NotNull @Pattern(regexp="[0-9]{6}") String oldCode,@NotNull @Pattern(regexp="[0-9]{6}") String newCode) {}
    @PostMapping("/send") public ApiResponse<Void> send(@Valid @RequestBody Send req,HttpServletRequest http) {
        email.send(EmailVerificationService.normalize(req.email()),req.purpose(),null,null,http.getRemoteAddr()); return ApiResponse.ok(null);
    }
    @PostMapping("/send-bind") public ApiResponse<Void> sendBind(@Valid @RequestBody BindSend req,HttpServletRequest http) {
        requireSession();email.send(EmailVerificationService.normalize(req.email()),"BIND",current.id(),current.sessionId(),http.getRemoteAddr());return ApiResponse.ok(null);
    }
    @PostMapping("/send-password") public ApiResponse<Void> sendPassword(HttpServletRequest http) {
        requireSession();email.send(users.currentEmail(),"PASSWORD",current.id(),current.sessionId(),http.getRemoteAddr());return ApiResponse.ok(null);
    }
    @PostMapping("/send-change") public ApiResponse<Void> sendChange(@Valid @RequestBody ChangeSend req,HttpServletRequest http) {
        requireSession();String oldEmail=users.currentEmail(),target=oldEmail;
        if(req.purpose().equals("CHANGE_NEW")) {
            if(req.newEmail()==null || req.newEmail().isBlank()) throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_PARAMETER","请输入新邮箱地址");
            target=EmailVerificationService.normalize(req.newEmail());
            if(oldEmail.equals(target)) throw new BusinessException(HttpStatus.CONFLICT,"EMAIL_UNCHANGED","新邮箱不能与原邮箱相同");
        }
        email.send(target,req.purpose(),current.id(),current.sessionId(),http.getRemoteAddr());return ApiResponse.ok(null);
    }
    @PostMapping("/register") public ApiResponse<TokenVO> register(@Valid @RequestBody Register req) {
        String address=EmailVerificationService.normalize(req.email());email.verify(address,"REGISTER",null,null,req.code());return ApiResponse.ok(users.registerEmail(address,req.password(),req.deviceName()));
    }
    @PostMapping("/code-login") public ApiResponse<TokenVO> codeLogin(@Valid @RequestBody CodeLogin req) {
        String address=EmailVerificationService.normalize(req.email());email.verify(address,"LOGIN",null,null,req.code());return ApiResponse.ok(users.loginEmail(address,req.deviceName()));
    }
    @PostMapping("/password-login") public ApiResponse<TokenVO> passwordLogin(@Valid @RequestBody PasswordLogin req,HttpServletRequest http) {
        String address=EmailVerificationService.normalize(req.email());email.limitPassword(address,http.getRemoteAddr());return ApiResponse.ok(users.passwordEmail(address,req.password(),req.deviceName()));
    }
    @PutMapping("/binding") public ApiResponse<com.wordtrail.user.vo.UserVO> bind(@Valid @RequestBody Bind req) {
        requireSession();
        if((req.phoneCode()==null)==(req.password()==null)) throw new com.wordtrail.common.exception.BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,"INVALID_PARAMETER","请使用密码或手机号验证码验证身份");
        if(req.phoneCode()!=null) sms.verify(users.currentPhone(),"EMAIL_BIND",current.id(),current.sessionId(),req.phoneCode());
        String address=EmailVerificationService.normalize(req.email());email.verify(address,"BIND",current.id(),current.sessionId(),req.code());users.bindEmail(address,req.password());return ApiResponse.ok(users.me(current.id()));
    }
    @PutMapping("/password") public ApiResponse<Void> password(@Valid @RequestBody PasswordChange req) {
        requireSession();String address=users.currentEmail();email.verify(address,"PASSWORD",current.id(),current.sessionId(),req.code());users.setEmailPassword(address,req.newPassword());return ApiResponse.ok(null);
    }
    @PutMapping("/change") public ApiResponse<Void> change(@Valid @RequestBody Change req) {
        requireSession();String oldEmail=users.currentEmail(),newEmail=EmailVerificationService.normalize(req.newEmail());
        if(oldEmail.equals(newEmail)) throw new BusinessException(HttpStatus.CONFLICT,"EMAIL_UNCHANGED","新邮箱不能与原邮箱相同");
        email.verifyChangeAndApply(oldEmail,newEmail,current.id(),current.sessionId(),req.oldCode(),req.newCode(),()->users.changeEmail(oldEmail,newEmail));return ApiResponse.ok(null);
    }
    private void requireSession() { if(current.sessionId()==null) throw new com.wordtrail.common.exception.BusinessException(org.springframework.http.HttpStatus.CONFLICT,"AUTH_SESSION_UPGRADE_REQUIRED","请重新登录后再绑定邮箱"); }
}
