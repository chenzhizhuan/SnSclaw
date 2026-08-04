package vip.mate.auth.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.auth.model.UserEntity;

/**
 * 用户 Mapper
 *
 * @author SnSclaw
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
