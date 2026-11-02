package com.jhwjhw.naverworkscloneapi.auth;

import jakarta.validation.constraints.NotNull;

// 관리자 모드 켜기·끄기 요청.
public record AdminModeRequest(@NotNull Boolean enabled) {
}
