package vip.mate.channel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import vip.mate.channel.feishu.FeishuClientFactory;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.repository.ChannelMapper;
import vip.mate.channel.tool.ChannelToolService;
import vip.mate.exception.MateClawException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChannelTypeValidationTest {
    @Mock ChannelMapper mapper;
    @Mock ObjectProvider<FeishuClientFactory> feishu;
    @Mock ObjectProvider<ChannelToolService> tools;
    private ChannelService service;

    @BeforeEach
    void setUp() {
        service = new ChannelService(mapper, new ObjectMapper(), feishu, tools);
    }

    private ChannelEntity channel(String type) {
        ChannelEntity channel = new ChannelEntity();
        channel.setName("Issue 654 regression");
        channel.setChannelType(type);
        channel.setEnabled(true);
        return channel;
    }

    @ParameterizedTest
    @ValueSource(strings = {"webhook", "unknown", "WEB", " web "})
    void rejectsUnsupportedCreateBeforeInsert(String type) {
        MateClawException error = assertThrows(MateClawException.class,
                () -> service.createChannel(channel(type)));
        assertEquals(400, error.getCode());
        assertEquals("err.channel.type_unsupported", error.getMsgKey());
        verifyNoInteractions(mapper);
    }

    @Test
    void disabledWebhookCannotBypassValidation() {
        ChannelEntity channel = channel("webhook");
        channel.setEnabled(false);
        assertThrows(MateClawException.class, () -> service.createChannel(channel));
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"web", "dingtalk", "feishu", "telegram", "discord", "wecom", "qq", "weixin", "slack", "webchat"})
    void supportedChannelsCanStillBeCreated(String type) {
        ChannelEntity channel = channel(type);
        assertSame(channel, service.createChannel(channel));
        verify(mapper).insert(channel);
        if ("webchat".equals(type)) {
            assertTrue(channel.getConfigJson().contains("api_key"));
        }
    }

    @Test
    void rejectsChangingExistingChannelToWebhookBeforeUpdate() {
        when(mapper.selectById(1L)).thenReturn(channel("web"));
        ChannelEntity update = channel("webhook");
        update.setId(1L);
        assertThrows(MateClawException.class, () -> service.updateChannel(update));
        verify(mapper, never()).updateById(any(ChannelEntity.class));
    }

    @Test
    void rejectsEnablingLegacyWebhookBeforeUpdate() {
        ChannelEntity legacy = channel("webhook");
        legacy.setEnabled(false);
        when(mapper.selectById(1L)).thenReturn(legacy);
        assertThrows(MateClawException.class, () -> service.toggleChannel(1L, true));
        assertFalse(legacy.getEnabled());
        verify(mapper, never()).updateById(any(ChannelEntity.class));
    }

    @Test
    void partialUpdateCannotEnableLegacyWebhook() {
        when(mapper.selectById(1L)).thenReturn(channel("webhook"));
        ChannelEntity update = new ChannelEntity();
        update.setId(1L);
        update.setEnabled(true);
        assertThrows(MateClawException.class, () -> service.updateChannel(update));
        verify(mapper, never()).updateById(any(ChannelEntity.class));
    }

    @Test
    void legacyWebhookCanStillBeDisabledAndDeleted() {
        ChannelEntity legacy = channel("webhook");
        when(mapper.selectById(1L)).thenReturn(legacy);
        assertFalse(service.toggleChannel(1L, false).getEnabled());
        service.deleteChannel(1L);
        verify(mapper).updateById(legacy);
        verify(mapper).deleteById(1L);
    }
}
