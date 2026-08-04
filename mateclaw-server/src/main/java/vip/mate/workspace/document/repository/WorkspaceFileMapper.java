package vip.mate.workspace.document.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

/**
 * 工作区文件 Mapper
 *
 * @author SnSclaw
 */
@Mapper
public interface WorkspaceFileMapper extends BaseMapper<WorkspaceFileEntity> {
}
