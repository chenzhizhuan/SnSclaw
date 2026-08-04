package vip.mate.workspace.core.annotation;

import java.lang.annotation.*;

/**
 * 声明式 Workspace 权限检查注解。
 * <p>
 * 标注在 Controller 方法上，拦截器会自动校验当前用户在请求 Workspace 中的角色 ≥ value()。
 * 如果方法参数或请求 Header 中没有 workspace 信息，走默认 workspace（id=1）。
 * <p>
 * 角色等级: owner(4) > admin(3) > member(2) > viewer(1)
 *
 * @author SnSclaw
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireWorkspaceRole {

    /**
     * 最低角色要求，默认 viewer（即只要是成员就可以访问）
     */
    String value() default "viewer";
}
