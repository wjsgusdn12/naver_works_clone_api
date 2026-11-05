package com.jhwjhw.naverworkscloneapi.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpSubscription;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

// 방에서 빠진 사람의 그 방 구독을 서버가 직접 끊음.
// 참여자 확인은 구독을 시작할 때만 하므로, 끊지 않으면 내보낸 뒤에도 열려 있는 탭으로 새 메시지가 계속 감.
// 다시 구독하려 하면 StompJwtChannelInterceptor가 참여자가 아니라서 거절.
// 한계: 이 서버에 붙은 연결만 끊음(서버 여러 대면 이벤트가 각 서버에 전달돼야 함 — 채팅 이벤트 서버 간 중계와 같은 과제).
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatSubscriptionRevoker {

    private final SimpUserRegistry userRegistry;
    private final ObjectProvider<SimpleBrokerMessageHandler> brokerProvider;

    public int revoke(Long membershipId, Long chatRoomId) {
        SimpleBrokerMessageHandler broker = brokerProvider.getIfAvailable();
        SimpUser user = userRegistry.getUser("membership:" + membershipId);
        if (broker == null || user == null) return 0;
        String destination = "/topic/chatRoom/" + chatRoomId;
        int revoked = 0;
        for (SimpSession session : user.getSessions()) {
            for (SimpSubscription subscription : session.getSubscriptions()) {
                if (!destination.equals(subscription.getDestination())) continue;
                broker.getSubscriptionRegistry().unregisterSubscription(unsubscribe(session.getId(), subscription.getId()));
                revoked++;
            }
        }
        log.debug("Chat room subscriptions revoked. membershipId={}, roomId={}, count={}", membershipId, chatRoomId,
                revoked);
        return revoked;
    }

    private static Message<byte[]> unsubscribe(String sessionId, String subscriptionId) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.UNSUBSCRIBE);
        headers.setSessionId(sessionId);
        headers.setSubscriptionId(subscriptionId);
        headers.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
