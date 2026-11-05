package com.jhwjhw.naverworkscloneapi.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jhwjhw.naverworkscloneapi.identity.domain.Company;
import com.jhwjhw.naverworkscloneapi.identity.domain.Membership;
import com.jhwjhw.naverworkscloneapi.identity.domain.Role;
import com.jhwjhw.naverworkscloneapi.identity.domain.UserAccount;
import com.jhwjhw.naverworkscloneapi.identity.repository.CompanyRepository;
import com.jhwjhw.naverworkscloneapi.identity.repository.MembershipRepository;
import com.jhwjhw.naverworkscloneapi.identity.repository.UserAccountRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpSubscription;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:chatRoomChangeTest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
@Transactional
@RecordApplicationEvents
// 방 이름·참여자·방장이 바뀌면 방 변경 이벤트가 나가고(초대·생성은 새 참여자, 내보냄·나감은 빠진 사람 표시),
// 빠진 사람의 그 방 구독은 서버 브로커에서 끊기는지 검증.
class ChatRoomChangeTests {
    @Autowired private ChatRoomService service;
    @Autowired private CompanyRepository companies;
    @Autowired private UserAccountRepository accounts;
    @Autowired private MembershipRepository memberships;
    @Autowired private ApplicationEvents events;
    @Autowired private ObjectProvider<SimpleBrokerMessageHandler> brokerProvider;

    private Membership owner;
    private Membership first;
    private Membership second;
    private Membership outsider;

    @BeforeEach
    void setUp() {
        Company company = companies.save(new Company("방 변경 테스트 " + UUID.randomUUID()));
        owner = createMember(company, "방장");
        first = createMember(company, "첫째");
        second = createMember(company, "둘째");
        outsider = createMember(company, "새 사람");
    }

    private Membership createMember(Company company, String name) {
        UserAccount account = accounts.save(new UserAccount(UUID.randomUUID() + "@example.com", "test-hash", name));
        return memberships.save(new Membership(company, account, null, Role.USER));
    }

    private Long group() {
        return service.createGroup(owner.getId(),
                new ChatRoomRequest.GroupCreateRequest("테스트 그룹", List.of(first.getId(), second.getId()))).id();
    }

    private List<ChatRoomChangedEvent> changes() {
        return events.stream(ChatRoomChangedEvent.class).toList();
    }

    @Test
    void creatingRoomsTellsTheOtherMembersOnly() {
        Long roomId = group();
        assertThat(changes()).singleElement().satisfies(event -> {
            assertThat(event.chatRoomId()).isEqualTo(roomId);
            // 만든 사람은 응답으로 방을 받으므로 제외
            assertThat(event.joinedMembershipIds()).containsExactlyInAnyOrder(first.getId(), second.getId());
        });

        Long directId = service.createDirect(owner.getId(), outsider.getId()).id();
        assertThat(changes().getLast().chatRoomId()).isEqualTo(directId);
        assertThat(changes().getLast().joinedMembershipIds()).containsExactly(outsider.getId());
    }

    @Test
    void inviteRemoveLeaveRenameAndOwnerTransferPublishRoomChanges() {
        Long roomId = group();
        int before = changes().size();

        service.invite(owner.getId(), roomId, outsider.getId());
        service.remove(owner.getId(), roomId, first.getId());
        service.leave(second.getId(), roomId);
        service.rename(owner.getId(), roomId, new ChatRoomRequest.RenameRequest("새 이름"));
        service.transferOwner(owner.getId(), roomId, outsider.getId());

        List<ChatRoomChangedEvent> published = changes().subList(before, changes().size());
        assertThat(published).allSatisfy(event -> assertThat(event.chatRoomId()).isEqualTo(roomId));
        assertThat(published).extracting(ChatRoomChangedEvent::joinedMembershipIds)
                .containsExactly(Set.of(outsider.getId()), Set.of(), Set.of(), Set.of(), Set.of());
        assertThat(published).extracting(ChatRoomChangedEvent::removedMembershipIds)
                .containsExactly(Set.of(), Set.of(first.getId()), Set.of(second.getId()), Set.of(), Set.of());
    }

    @Test
    void companyMoveRemovesTheMemberFromEveryRoom() {
        Long roomId = group();
        service.leaveAllForCompanyMove(first.getId());
        assertThat(changes().getLast().chatRoomId()).isEqualTo(roomId);
        assertThat(changes().getLast().removedMembershipIds()).containsExactly(first.getId());
    }

    // 실제 앱 설정에서 간단 브로커를 찾을 수 있어야 구독을 끊을 수 있음
    @Test
    void appContextProvidesTheSimpleBroker() {
        assertThat(brokerProvider.getIfAvailable()).isNotNull();
    }

    @Test
    void revokerRemovesOnlyThatRoomSubscriptionOfThatMember() {
        SimpleBrokerMessageHandler broker = new SimpleBrokerMessageHandler(new ExecutorSubscribableChannel(),
                new ExecutorSubscribableChannel(), new ExecutorSubscribableChannel(), List.of("/topic", "/queue"));
        // 같은 연결에서 방 7(빠질 방)과 방 8(남는 방)을 구독 중
        broker.getSubscriptionRegistry().registerSubscription(frame(SimpMessageType.SUBSCRIBE, "s1", "sub-7",
                "/topic/chatRoom/7"));
        broker.getSubscriptionRegistry().registerSubscription(frame(SimpMessageType.SUBSCRIBE, "s1", "sub-8",
                "/topic/chatRoom/8"));

        SimpUser user = mock(SimpUser.class);
        SimpSession session = mock(SimpSession.class);
        SimpSubscription room7 = subscription("sub-7", "/topic/chatRoom/7");
        SimpSubscription room8 = subscription("sub-8", "/topic/chatRoom/8");
        when(session.getId()).thenReturn("s1");
        when(session.getSubscriptions()).thenReturn(Set.of(room7, room8));
        when(user.getSessions()).thenReturn(Set.of(session));
        SimpUserRegistry registry = mock(SimpUserRegistry.class);
        when(registry.getUser("membership:5")).thenReturn(user);
        ObjectProvider<SimpleBrokerMessageHandler> provider = new StaticListableBeanFactory(Map.of("broker", broker))
                .getBeanProvider(SimpleBrokerMessageHandler.class);

        assertThat(new ChatSubscriptionRevoker(registry, provider).revoke(5L, 7L)).isEqualTo(1);

        assertThat(broker.getSubscriptionRegistry().findSubscriptions(publish("/topic/chatRoom/7"))).isEmpty();
        assertThat(broker.getSubscriptionRegistry().findSubscriptions(publish("/topic/chatRoom/8"))).isNotEmpty();
        // 접속하지 않은 사람이면 할 일 없음
        assertThat(new ChatSubscriptionRevoker(registry, provider).revoke(6L, 7L)).isZero();
    }

    private static SimpSubscription subscription(String id, String destination) {
        SimpSubscription subscription = mock(SimpSubscription.class);
        when(subscription.getId()).thenReturn(id);
        when(subscription.getDestination()).thenReturn(destination);
        return subscription;
    }

    private static Message<byte[]> frame(SimpMessageType type, String sessionId, String subscriptionId,
            String destination) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(type);
        headers.setSessionId(sessionId);
        headers.setSubscriptionId(subscriptionId);
        headers.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    private static Message<byte[]> publish(String destination) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
