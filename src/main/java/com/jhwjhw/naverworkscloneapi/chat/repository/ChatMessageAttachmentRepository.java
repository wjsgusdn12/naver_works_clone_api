package com.jhwjhw.naverworkscloneapi.chat.repository;

import com.jhwjhw.naverworkscloneapi.chat.domain.ChatAttachmentKind;
import com.jhwjhw.naverworkscloneapi.chat.domain.ChatMessageAttachment;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageAttachmentRepository extends JpaRepository<ChatMessageAttachment, Long> {
    Optional<ChatMessageAttachment> findByChatMessageId(Long chatMessageId);

    boolean existsByChatMessageId(Long chatMessageId);

    // 업로드 속도 제한: since 이후 이 사람이 직접 올린 첨부 수(전달한 복사본은 새 파일이 아니라 빼고 셈).
    @Query("""
            select count(a) from ChatMessageAttachment a join a.chatMessage m
            where m.senderMembership.id = :membershipId and m.forwardedFromMessageId is null
              and a.createdAt >= :since
            """)
    long countUploadsSince(@Param("membershipId") Long membershipId, @Param("since") Instant since);

    // 업로드 용량 제한: since 이후 이 사람이 직접 올린 첨부 크기 합(바이트).
    @Query("""
            select coalesce(sum(a.size), 0) from ChatMessageAttachment a join a.chatMessage m
            where m.senderMembership.id = :membershipId and m.forwardedFromMessageId is null
              and a.createdAt >= :since
            """)
    long sumUploadBytesSince(@Param("membershipId") Long membershipId, @Param("since") Instant since);

    // 청소: 보관 기간이 끝난 첨부(메시지 삭제 또는 방 종료가 cutoff보다 이전)이고 아직 정리 표시가 없는 것.
    // afterId보다 뒤의 것만 id 순서로(묶음 단위로 이어 읽기).
    @Query("""
            select a from ChatMessageAttachment a join a.chatMessage m join a.chatRoom r
            where a.purgedAt is null and a.id > :afterId
              and ((m.deletedAt is not null and m.deletedAt < :cutoff)
                   or (r.endedAt is not null and r.endedAt < :cutoff))
            order by a.id
            """)
    List<ChatMessageAttachment> findExpired(@Param("cutoff") Instant cutoff, @Param("afterId") long afterId,
            Pageable pageable);

    // 청소: 같은 파일을 아직 쓰는 첨부(정리 표시가 없고 보관 기간도 안 끝난 것)가 있는지. 전달한 복사본 보호용.
    @Query("""
            select count(a) > 0 from ChatMessageAttachment a join a.chatMessage m join a.chatRoom r
            where a.storageKey = :storageKey and a.purgedAt is null
              and not ((m.deletedAt is not null and m.deletedAt < :cutoff)
                       or (r.endedAt is not null and r.endedAt < :cutoff))
            """)
    boolean existsInUse(@Param("storageKey") String storageKey, @Param("cutoff") Instant cutoff);

    // 청소: 같은 파일을 가리키는 모든 첨부(정리 표시를 함께 남기기 위해).
    List<ChatMessageAttachment> findByStorageKey(String storageKey);

    // 청소: 디스크 파일 키 중 DB 첨부가 가리키는(원본·썸네일) 키만 골라냄. 나머지는 고아 파일.
    @Query("select a.storageKey from ChatMessageAttachment a where a.storageKey in :keys")
    List<String> findReferencedStorageKeys(@Param("keys") Collection<String> keys);

    @Query("select a.thumbnailKey from ChatMessageAttachment a where a.thumbnailKey in :keys")
    List<String> findReferencedThumbnailKeys(@Param("keys") Collection<String> keys);

    // 메시지 페이지의 첨부를 한 번에 읽음.
    List<ChatMessageAttachment> findByChatMessageIdIn(Collection<Long> chatMessageIds);

    // 모아보기: 삭제되지 않은 메시지의 첨부를 최신순으로. cursor보다 오래된 것만.
    @Query("""
            select a from ChatMessageAttachment a
            join fetch a.chatMessage m
            join fetch m.senderMembership s
            join fetch s.userAccount
            where a.chatRoom.id = :roomId and a.kind = :kind and m.deletedAt is null and a.id < :cursor
            order by a.id desc
            """)
    List<ChatMessageAttachment> findGallery(@Param("roomId") Long roomId, @Param("kind") ChatAttachmentKind kind,
            @Param("cursor") Long cursor, Pageable pageable);

    // 상단 검색 [파일] 오른쪽 목록: 고른 방(내가 참여 중, 숨긴 방 포함)에서 파일 이름에 키워드가 들어간 첨부를 최신순으로.
    // pattern은 '%키워드%' 형태이며 키워드 속 %·_는 !로 이스케이프돼 글자 그대로 찾음.
    @Query("""
            select a from ChatMessageAttachment a
            join fetch a.chatMessage m
            join fetch m.senderMembership s
            join fetch s.userAccount
            join fetch a.chatRoom r
            where r.id in (select member.chatRoom.id from ChatRoomMember member
                           where member.membership.id = :membershipId and member.leftAt is null)
              and r.endedAt is null and m.deletedAt is null
              and lower(a.originalName) like :pattern escape '!'
              and r.id = :roomId
              and a.id < :cursor
            order by a.id desc
            """)
    List<ChatMessageAttachment> searchByName(@Param("membershipId") Long membershipId,
            @Param("pattern") String pattern, @Param("roomId") Long roomId, @Param("cursor") Long cursor,
            Pageable pageable);

    // 상단 검색 [파일] 왼쪽 방 목록: 위와 같은 조건으로 방별 개수를 셈. 최근에 맞는 파일이 있는 방이 위.
    @Query("""
            select r.id as roomId, count(a) as matchCount, max(a.id) as latestId
            from ChatMessageAttachment a join a.chatMessage m join a.chatRoom r
            where r.id in (select member.chatRoom.id from ChatRoomMember member
                           where member.membership.id = :membershipId and member.leftAt is null)
              and r.endedAt is null and m.deletedAt is null
              and lower(a.originalName) like :pattern escape '!'
            group by r.id
            order by max(a.id) desc
            """)
    List<ChatSearchRoomCount> countSearchByRoom(@Param("membershipId") Long membershipId,
            @Param("pattern") String pattern);
}
