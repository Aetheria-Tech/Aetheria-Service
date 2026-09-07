package com.serverbe.adapter.in.web;

import com.serverbe.adapter.in.web.dto.me.UserProfileResponse;
import com.serverbe.adapter.in.web.dto.me.UserUpdateRequest;
import com.serverbe.adapter.in.web.response.RestApiResponse;
import com.serverbe.application.annotation.RateLimit;
import com.serverbe.application.annotation.RateLimits;
import com.serverbe.application.port.in.me.UpdateUserUseCase;
import com.serverbe.application.port.in.me.GetUserUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "User", description = "사용자 관리 API")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final GetUserUseCase getUserUseCase;
    private final UpdateUserUseCase updateUserUseCase;

    @Operation(
            summary = "내 프로필 조회",
            description = "로그인된 사용자의 프로필 정보(닉네임, 이메일, 프로필 이미지 등)를 조회합니다.",
            security = @SecurityRequirement(name = "jwtAuth"),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "조회 성공",
                            useReturnTypeSchema = true
                    ),
                    @ApiResponse(responseCode = "401", description = "인증 실패 (토큰 만료 또는 누락)"),
                    @ApiResponse(responseCode = "404", description = "사용자 정보를 찾을 수 없음")
            }
    )
    @RateLimits(
            @RateLimit(target = RateLimit.TargetType.IP, capacity = 3, refillRate = 1)
    )
    @GetMapping("/me")
    public ResponseEntity<RestApiResponse<UserProfileResponse>> getMyProfile(@Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(RestApiResponse.success(
                UserProfileResponse.toResponse(getUserUseCase.getMyProfile(userId))
        ));
    }

    @Operation(
            summary = "내 프로필 수정",
            description = "닉네임, 상태 메시지 등 내 프로필 정보를 수정합니다. 수정 후 변경된 프로필 정보를 반환합니다.",
            security = @SecurityRequirement(name = "jwtAuth"),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "수정 성공",
                            useReturnTypeSchema = true
                    ),
                    @ApiResponse(responseCode = "400", description = "잘못된 입력 값 (유효성 검증 실패)"),
                    @ApiResponse(responseCode = "401", description = "인증 실패"),
                    @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음")
            }
    )
    @RateLimits(
            @RateLimit(target = RateLimit.TargetType.IP, capacity = 1, refillRate = 1)
    )
    @PatchMapping("/me")
    public ResponseEntity<RestApiResponse<UserProfileResponse>> updateMyProfile(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @RequestBody @Valid UserUpdateRequest request
    ) {
        return ResponseEntity.ok(RestApiResponse.success(
                UserProfileResponse.toResponse(updateUserUseCase.updateMyProfile(userId, request.toCommand()))
        ));
    }
}