package vip.mate.datasource.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.datasource.model.DatasourceEntity;

/**
 * 数据源 Mapper
 *
 * @author SnSclaw Team
 */
@Mapper
public interface DatasourceMapper extends BaseMapper<DatasourceEntity> {
}
