package vip.mate.task.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.task.model.AsyncTaskEntity;

/**
 * 异步任务 Mapper
 *
 * @author SnSclaw Team
 */
@Mapper
public interface AsyncTaskMapper extends BaseMapper<AsyncTaskEntity> {
}
