package com.jhwjhw.naverworkscloneapi.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChatRealtimeEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatSubscriptionRevoker subscriptionRevoker;

    // 빠진 사람은 구독부터 끊은 뒤 알려서, 남은 참여자용 알림·이후 메시지가 그 사람에게 가지 않게 함.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishRoomChanged(ChatRoomChangedEvent event) {
        Long roomId = event.chatRoomId();
        event.removedMembershipIds().forEach(id -> subscriptionRevoker.revoke(id, roomId));
        messagingTemplate.convertAndSend("/topic/chatRoom/" + roomId, new ChatRoomRealtimeEvent("ROOM_UPDATED", roomId));
        event.joinedMembershipIds().forEach(id -> messagingTemplate.convertAndSendToUser("membership:" + id,
                "/queue/chatRoom", new ChatRoomRealtimeEvent("ROOM_JOINED", roomId)));
        event.removedMembershipIds().forEach(id -> messagingTemplate.convertAndSendToUser("membership:" + id,
                "/queue/chatRoom", new ChatRoomRealtimeEvent("ROOM_REMOVED", roomId)));
        log.debug("Realtime room change published. roomId={}, joined={}, removed={}", roomId,
                event.joinedMembershipIds(), event.removedMembershipIds());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishMessageCreated(ChatMessageCreatedEvent event) {
        String destination = "/topic/chatRoom/" + event.chatRoomId();
        messagingTemplate.convertAndSend(destination,
                new ChatRealtimeEvent("MESSAGE_CREATED", event.chatRoomId(), event.message()));
        log.debug("Realtime chat message published. roomId={}, messageId={}", event.chatRoomId(),
                event.message().id());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishMessageChanged(ChatMessageChangedEvent event) {
        messagingTemplate.convertAndSend("/topic/chatRoom/" + event.chatRoomId(),
                new ChatRealtimeEvent(event.type(), event.chatRoomId(), event.message()));
        log.debug("Realtime message change published. type={}, roomId={}, messageId={}, version={}",
                event.type(), event.chatRoomId(), event.message().id(), event.message().version());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishReactionChanged(ChatReactionChangedEvent event) {
        messagingTemplate.convertAndSend("/topic/chatRoom/" + event.chatRoomId(),
                new ChatReactionRealtimeEvent("REACTION_UPDATED", event.chatRoomId(), event.messageId(),
                        event.emoji(), event.membershipId(), event.action()));
        log.debug("Realtime reaction change published. roomId={}, messageId={}, emoji={}, membershipId={}, action={}",
                event.chatRoomId(), event.messageId(), event.emoji(), event.membershipId(), event.action());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishNoticeChanged(ChatNoticeChangedEvent event) {
        messagingTemplate.convertAndSend("/topic/chatRoom/" + event.chatRoomId(),
                new ChatNoticeRealtimeEvent("NOTICE_UPDATED", event.chatRoomId(), event.notices()));
        log.debug("Realtime notice change published. roomId={}, count={}", event.chatRoomId(),
                event.notices().size());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishMessageReadUpdated(ChatMessageReadUpdatedEvent event) {
        messagingTemplate.convertAndSend("/topic/chatRoom/" + event.chatRoomId(),
                new ChatReadRealtimeEvent("READ_UPDATED", event.chatRoomId(), event.readerMembershipId(),
                        event.lastReadMessageId()));
        log.debug("Realtime chat read state published. roomId={}, readerId={}, lastReadMessageId={}",
                event.chatRoomId(), event.readerMembershipId(), event.lastReadMessageId());
    }
}
