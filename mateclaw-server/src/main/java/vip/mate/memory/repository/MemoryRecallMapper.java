package vip.mate.memory.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.memory.model.MemoryRecallEntity;

/**
 * 记忆召回追踪 Mapper
 *
 * @author SnSclaw Team
 */
@Mapper
public interface MemoryRecallMapper extends BaseMapper<MemoryRecallEntity> {
}
