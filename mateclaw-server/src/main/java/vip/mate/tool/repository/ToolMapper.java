package vip.mate.tool.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.tool.model.ToolEntity;

/**
 * 工具 Mapper
 *
 * @author SnSclaw
 */
@Mapper
public interface ToolMapper extends BaseMapper<ToolEntity> {
}
