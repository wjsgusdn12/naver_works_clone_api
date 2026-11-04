package com.jhwjhw.naverworkscloneapi.profile;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

// realtime:profile-image 채널을 듣는 장치(배포용)
@Configuration
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisProfileImageConfig {

    @Bean
    RedisMessageListenerContainer profileImageListenerContainer(RedisConnectionFactory connectionFactory,
            RedisProfileImageBroadcaster broadcaster) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(broadcaster, new ChannelTopic(RedisProfileImageBroadcaster.CHANNEL));
        return container;
    }
}
