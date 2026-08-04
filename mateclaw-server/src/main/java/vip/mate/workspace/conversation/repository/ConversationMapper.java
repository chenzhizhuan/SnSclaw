package vip.mate.workspace.conversation.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.workspace.conversation.model.ConversationEntity;

/**
 * 会话 Mapper
 *
 * @author SnSclaw
 */
@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {
}
