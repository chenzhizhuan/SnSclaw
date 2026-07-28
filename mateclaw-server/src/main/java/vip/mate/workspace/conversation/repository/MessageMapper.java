package vip.mate.workspace.conversation.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.workspace.conversation.model.MessageEntity;

/**
 * 消息数据访问层
 *
 * @author SnSclaw Team
 */
@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {
}
