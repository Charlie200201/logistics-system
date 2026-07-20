package com.logistics.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.logistics.user.entity.User;

public interface UserService extends IService<User> {
    User register(String username, String password, String phone);
    String login(String username, String password);
    User getUserById(Long id);
    boolean validateToken(String token);
}
