package vip.mate.planning.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.planning.model.SubPlanEntity;

/**
 * 子计划 Mapper
 *
 * @author SnSclaw Team
 */
@Mapper
public interface SubPlanMapper extends BaseMapper<SubPlanEntity> {
}
