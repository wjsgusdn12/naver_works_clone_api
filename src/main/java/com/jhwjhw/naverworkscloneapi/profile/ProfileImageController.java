package com.jhwjhw.naverworkscloneapi.profile;

import com.jhwjhw.naverworkscloneapi.common.response.ApiResponse;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// 프로필 사진: 올리기·기본으로 되돌리기(본인), 사진이 있는 사람 목록·사진 보기(로그인한 구성원).
@RestController
@RequestMapping("/api/v1/profile/image")
@RequiredArgsConstructor
public class ProfileImageController {
    private final ProfileImageService service;

    // 사진이 있는 구성원 ID → 판(사진이 바뀌면 달라짐). 화면은 이 목록에 있는 사람만 사진을 받음.
    @GetMapping("/list")
    public ApiResponse<Map<Long, String>> list() {
        return ApiResponse.success("PROFILE_IMAGE_LIST_FOUND", "프로필 사진 목록을 조회했습니다.", service.versions());
    }

    @PostMapping("/upload")
    public ApiResponse<Map<Long, String>> upload(@AuthenticationPrincipal Jwt jwt,
            @RequestParam("file") MultipartFile file) {
        service.change(id(jwt), file);
        return ApiResponse.success("PROFILE_IMAGE_CHANGED", "프로필 사진을 바꿨습니다.", service.versions());
    }

    @PostMapping("/remove")
    public ApiResponse<Map<Long, String>> remove(@AuthenticationPrincipal Jwt jwt) {
        service.remove(id(jwt));
        return ApiResponse.success("PROFILE_IMAGE_REMOVED", "기본 이미지로 바꿨습니다.", service.versions());
    }

    // 사진 파일(JPEG). 판이 주소에 붙어 오므로 브라우저가 오래 보관해도 됨(사진이 바뀌면 판이 달라짐).
    @GetMapping("/{membershipId}")
    public ResponseEntity<Resource> image(@PathVariable Long membershipId) {
        Resource resource = service.load(membershipId);
        if (resource == null || !resource.exists()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                .body(resource);
    }

    private static Long id(Jwt jwt) {
        return jwt.getClaim("membershipId");
    }
}
