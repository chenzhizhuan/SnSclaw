package vip.mate.agent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.agent.model.AgentEntity;

/**
 * Agent 数据访问层
 *
 * @author SnSclaw
 */
@Mapper
public interface AgentMapper extends BaseMapper<AgentEntity> {
}
