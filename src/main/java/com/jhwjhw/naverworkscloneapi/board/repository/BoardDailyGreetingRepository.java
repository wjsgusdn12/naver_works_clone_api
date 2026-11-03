package com.jhwjhw.naverworkscloneapi.board.repository;

import com.jhwjhw.naverworkscloneapi.board.domain.BoardDailyGreeting;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoardDailyGreetingRepository extends JpaRepository<BoardDailyGreeting, LocalDate> {
}
