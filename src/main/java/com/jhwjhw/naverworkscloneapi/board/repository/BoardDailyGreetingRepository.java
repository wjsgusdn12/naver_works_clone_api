package com.jhwjhw.naverworkscloneapi.board.repository;

import com.jhwjhw.naverworkscloneapi.board.domain.BoardDailyGreeting;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoardDailyGreetingRepository extends JpaRepository<BoardDailyGreeting, LocalDate> {
    // 증시 분석(미국·한국)·뉴스 중 아직 끝나지 않은 부분이 있는 날짜들, 오래된 날짜부터
    @Query("select g from BoardDailyGreeting g where g.usDoneAt is null or g.krDoneAt is null or g.newsDoneAt is null "
            + "order by g.greetingDate")
    List<BoardDailyGreeting> findReportPending();

    // from 이후 날짜들, 오래된 날짜부터(예전 형식으로 채운 부분을 다시 만들 때 함께 봄)
    List<BoardDailyGreeting> findByGreetingDateGreaterThanEqualOrderByGreetingDate(LocalDate from);

    // 비어 있거나 기한이 지난 작업 자리를 잡음. 1이면 이 서버가 잡은 것, 0이면 다른 서버가 작업 중.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update BoardDailyGreeting g set g.leaseUntil = :until "
            + "where g.greetingDate = :date and (g.leaseUntil is null or g.leaseUntil < :now)")
    int claim(@Param("date") LocalDate date, @Param("now") Instant now, @Param("until") Instant until);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update BoardDailyGreeting g set g.leaseUntil = null where g.greetingDate = :date")
    int release(@Param("date") LocalDate date);
}
