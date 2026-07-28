package vip.mate.planning.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.planning.model.PlanEntity;

/**
 * 计划 Mapper
 *
 * @author SnSclaw Team
 */
@Mapper
public interface PlanMapper extends BaseMapper<PlanEntity> {
}
