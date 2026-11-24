package com.canteen.controller;

import com.canteen.common.Result;
import com.canteen.dto.LoginDTO;
import com.canteen.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success("登录成功", authService.login(dto));
    }

    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        return Result.success(authService.me());
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }

    /**
     * 用户自助修改密码。首次登录被要求改密时走的也是这个接口。
     * 不走管理员重置，避免"改自己的密码还得找管理员"。
     */
    @PutMapping("/password")
    public Result<Map<String, Object>> changePassword(@RequestBody Map<String, String> body) {
        return Result.success("密码已修改", authService.changePassword(
                body.get("currentPassword"), body.get("newPassword")));
    }

    @GetMapping("/roles")
    public Result<java.util.List<Map<String, Object>>> roles() {
        return Result.success(authService.roles());
    }
}
