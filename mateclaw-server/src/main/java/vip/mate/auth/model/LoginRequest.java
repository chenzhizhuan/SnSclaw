package vip.mate.auth.model;

import lombok.Data;

/**
 * 登录请求
 *
 * @author SnSclaw Team
 */
@Data
public class LoginRequest {
    private String username;
    private String password;
}
