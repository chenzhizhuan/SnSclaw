package vip.mate.wiki.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Wiki 处理事件
 * <p>
 * 当原始材料需要被 AI 消化时发布此事件。
 *
 * @author SnSclaw Team
 */
@Getter
public class WikiProcessingEvent extends ApplicationEvent {

    private final Long rawMaterialId;
    private final Long kbId;

    public WikiProcessingEvent(Object source, Long rawMaterialId, Long kbId) {
        super(source);
        this.rawMaterialId = rawMaterialId;
        this.kbId = kbId;
    }
}
