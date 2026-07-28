package vip.mate.plugin.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.plugin.model.PluginEntity;

/**
 * MyBatis Plus mapper for mate_plugin table.
 *
 * @author SnSclaw Team
 */
@Mapper
public interface PluginMapper extends BaseMapper<PluginEntity> {
}
