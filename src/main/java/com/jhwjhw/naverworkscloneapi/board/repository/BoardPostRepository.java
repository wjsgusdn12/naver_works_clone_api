package com.jhwjhw.naverworkscloneapi.board.repository;

import com.jhwjhw.naverworkscloneapi.board.domain.BoardPost;
import com.jhwjhw.naverworkscloneapi.board.domain.BoardType;
import com.jhwjhw.naverworkscloneapi.board.domain.PostStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoardPostRepository extends JpaRepository<BoardPost, Long> {
    // 검색어 조건: search가 false면 적용하지 않음. keyword는 소문자 like 패턴, scope는 찾을 곳
    // (ALL: 제목·본문·글쓴이, POST: 제목·본문, AUTHOR: 글쓴이 이름, FILE: 첨부 파일 이름, COMMENT: 지우지 않은 댓글).
    // field는 제목·본문 중 찾을 칸(ALL·TITLE·CONTENT). 검색 필터: 등록 시각 from 이상 to 미만,
    // fileOnly면 첨부 파일(본문 안 그림 제외)이 있는 글만. 필터를 안 쓰면 from·to는 아주 이른·늦은 시각.
    String SEARCH = "and (:search = false "
            + "or ((:scope = 'ALL' or :scope = 'POST') "
            + "and ((:field <> 'CONTENT' and lower(p.title) like :keyword escape '\\') "
            + "or (:field <> 'TITLE' and lower(p.contentText) like :keyword escape '\\'))) "
            + "or ((:scope = 'ALL' or :scope = 'AUTHOR') and lower(u.displayName) like :keyword escape '\\') "
            + "or (:scope = 'FILE' and exists (select f.id from BoardAttachment f where f.post = p "
            + "and f.inlineImage = false and lower(f.originalName) like :keyword escape '\\')) "
            + "or (:scope = 'COMMENT' and exists (select c.id from BoardComment c where c.post = p "
            + "and c.deletedAt is null and lower(c.content) like :keyword escape '\\'))) "
            + "and p.createdAt >= :from and p.createdAt < :to "
            + "and (:fileOnly = false or exists (select f2.id from BoardAttachment f2 where f2.post = p "
            + "and f2.inlineImage = false)) ";

    // 목록·검색은 등록된 글만(임시저장·예약 글은 쓴 사람의 임시저장함·예약함에서만)
    String PUBLISHED = "and p.status = com.jhwjhw.naverworkscloneapi.board.domain.PostStatus.PUBLISHED ";

    String PAGE_WHERE = "where b.id in :boardIds and p.deletedAt is null " + PUBLISHED
            + "and (:admin = true or b.type <> :hiddenType or a.id = :me) "
            + "and (:mustRead = false or p.mustRead = true) "
            // important: 내가 중요 표시(깃발)한 글만
            + "and (:important = false or exists (select i.id from BoardPostImportant i "
            + "where i.post = p and i.membership.id = :me)) "
            + "and (:mine = false or a.id = :me) "
            + "and (:unread = false or not exists (select r.id from BoardPostRead r "
            + "where r.post = p and r.membership.id = :me)) " + SEARCH;

    // 글 목록(쪽 번호). 볼 수 있는 게시판(boardIds) 안에서만 찾고,
    // 관리자가 아니면 남의 문의하기 글(hiddenType)은 뺌. 필터는 false면 적용하지 않음(unread: 내가 안 읽은 글만).
    // 정렬: activity면 글/댓글 업데이트 순(이전 글은 등록 시각으로), 아니면 등록순. 둘 다 최신이 위.
    @Query(value = "select p from BoardPost p join fetch p.board b join fetch b.company join fetch p.author a "
            + "join fetch a.userAccount u " + PAGE_WHERE
            + "order by case when :activity = true then coalesce(p.activityAt, p.createdAt) else p.createdAt end "
            + "desc, p.id desc",
            countQuery = "select count(p) from BoardPost p join p.board b join p.author a join a.userAccount u "
                    + PAGE_WHERE)
    Page<BoardPost> findPage(@Param("boardIds") Collection<Long> boardIds, @Param("me") Long me,
            @Param("admin") boolean admin, @Param("hiddenType") BoardType hiddenType,
            @Param("mustRead") boolean mustRead, @Param("important") boolean important,
            @Param("mine") boolean mine,
            @Param("unread") boolean unread, @Param("search") boolean search, @Param("keyword") String keyword,
            @Param("scope") String scope, @Param("field") String field, @Param("from") Instant from,
            @Param("to") Instant to, @Param("fileOnly") boolean fileOnly, @Param("activity") boolean activity,
            Pageable pageable);

    // 검색 결과 탭의 건수(목록과 같은 조건, 글은 가져오지 않음)
    @Query("select count(p) from BoardPost p join p.board b join p.author a join a.userAccount u " + PAGE_WHERE)
    long countPage(@Param("boardIds") Collection<Long> boardIds, @Param("me") Long me,
            @Param("admin") boolean admin, @Param("hiddenType") BoardType hiddenType,
            @Param("mustRead") boolean mustRead, @Param("important") boolean important,
            @Param("mine") boolean mine,
            @Param("unread") boolean unread, @Param("search") boolean search, @Param("keyword") String keyword,
            @Param("scope") String scope, @Param("field") String field, @Param("from") Instant from,
            @Param("to") Instant to, @Param("fileOnly") boolean fileOnly);

    String TRASH_WHERE = "where b.id in :boardIds and p.deletedAt is not null and p.deletedAt > :since " + PUBLISHED
            + "and (:admin = true or p.deletedBy.id = :me) " + SEARCH;

    // 휴지통: since 이후에 지운 글. 관리자가 아니면 내가 지운 글만.
    @Query(value = "select p from BoardPost p join fetch p.board b join fetch b.company join fetch p.author a "
            + "join fetch a.userAccount u " + TRASH_WHERE + "order by p.id desc",
            countQuery = "select count(p) from BoardPost p join p.board b join p.author a join a.userAccount u "
                    + TRASH_WHERE)
    Page<BoardPost> findTrash(@Param("boardIds") Collection<Long> boardIds, @Param("me") Long me,
            @Param("admin") boolean admin, @Param("since") Instant since, @Param("search") boolean search,
            @Param("keyword") String keyword, @Param("scope") String scope, @Param("field") String field,
            @Param("from") Instant from, @Param("to") Instant to, @Param("fileOnly") boolean fileOnly,
            Pageable pageable);

    @Query("select p from BoardPost p join fetch p.board b join fetch b.company left join fetch b.department "
            + "join fetch p.author a join fetch a.userAccount left join fetch a.department where p.id = :id")
    Optional<BoardPost> findDetailById(@Param("id") Long id);

    // 조회수 1 올리기. 수정 충돌 검사(version)와 겹치지 않게 엔티티를 거치지 않고 바로 바꿈.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BoardPost p set p.viewCount = p.viewCount + 1 where p.id = :id")
    void increaseViewCount(@Param("id") Long id);

    // 댓글 수 바꾸기(delta: +1·-1). 위와 같은 이유로 version을 올리지 않음(댓글이 달려도 글 수정이 충돌로 막히지 않게).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BoardPost p set p.commentCount = p.commentCount + :delta "
            + "where p.id = :id and p.commentCount + :delta >= 0")
    void changeCommentCount(@Param("id") Long id, @Param("delta") int delta);

    // 임시저장함·예약함: 내 글 중 그 상태(휴지통 제외). 최근 것부터.
    @Query(value = "select p from BoardPost p join fetch p.board b join fetch b.company join fetch p.author a "
            + "join fetch a.userAccount where a.id = :me and p.status = :status and p.deletedAt is null "
            + "order by p.id desc",
            countQuery = "select count(p) from BoardPost p where p.author.id = :me and p.status = :status "
                    + "and p.deletedAt is null")
    Page<BoardPost> findMine(@Param("me") Long me, @Param("status") PostStatus status, Pageable pageable);

    // 예약 시각이 지난 예약 글
    @Query("select p.id from BoardPost p where p.status = com.jhwjhw.naverworkscloneapi.board.domain.PostStatus.SCHEDULED "
            + "and p.publishAt <= :now and p.deletedAt is null order by p.publishAt")
    List<Long> findDueScheduledIds(@Param("now") Instant now);

    // 예약 글 공개. 아직 예약 상태일 때만 바꿔서(서버가 여러 대여도) 한 번만 공개되게 함. 바뀐 행 수를 돌려줌.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BoardPost p set p.status = com.jhwjhw.naverworkscloneapi.board.domain.PostStatus.PUBLISHED, "
            + "p.publishAt = null, p.createdAt = :now, p.activityAt = :now where p.id = :id "
            + "and p.status = com.jhwjhw.naverworkscloneapi.board.domain.PostStatus.SCHEDULED and p.deletedAt is null")
    int publishScheduled(@Param("id") Long id, @Param("now") Instant now);

    // 댓글이 달린 시각을 마지막 활동으로. 댓글 수와 같은 이유로 version을 올리지 않음.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BoardPost p set p.activityAt = :at where p.id = :id")
    void touchActivity(@Param("id") Long id, @Param("at") Instant at);

    // 문의하기 글에 관리자가 처음 답변한 시각. 이미 있으면 그대로.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BoardPost p set p.answeredAt = :at where p.id = :id and p.answeredAt is null")
    void markAnswered(@Param("id") Long id, @Param("at") Instant at);
}
