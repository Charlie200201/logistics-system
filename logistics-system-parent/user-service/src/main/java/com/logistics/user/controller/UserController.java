package com.logistics.user.controller;

import com.logistics.common.result.Result;
import com.logistics.user.entity.User;
import com.logistics.user.service.UserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Api(tags = "用户服务")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @ApiOperation("用户注册")
    @PostMapping("/register")
    public Result<User> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String phone = body.get("phone");
        User user = userService.register(username, password, phone);
        user.setPassword(null);
        return Result.ok(user);
    }

    @ApiOperation("用户登录")
    @PostMapping("/login")
    public Result<Map<String, String>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String token = userService.login(username, password);
        return Result.ok(Map.of("token", token));
    }

    @ApiOperation("根据ID查询用户")
    @GetMapping("/{id}")
    public Result<User> getUserById(@ApiParam("用户ID") @PathVariable Long id) {
        return Result.ok(userService.getUserById(id));
    }

    @ApiOperation("验证Token（内部接口）")
    @GetMapping("/verify")
    public Result<Boolean> verifyToken(@RequestParam("token") String token) {
        return Result.ok(userService.validateToken(token));
    }
}
