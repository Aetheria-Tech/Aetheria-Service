package com.serverbe.adapter.in.web;

import com.serverbe.adapter.in.web.dto.art.CreateRunningArtRequest;
import com.serverbe.adapter.in.web.support.PageQueryMapper;
import com.serverbe.application.port.dto.PageResult;
import com.serverbe.adapter.in.web.dto.art.RunningArtResponse;
import com.serverbe.application.annotation.RateLimit;
import com.serverbe.application.port.in.art.*;
import com.serverbe.adapter.in.web.dto.art.UpdateRunningArtRequest;
import com.serverbe.adapter.in.web.response.RestApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@Tag(name = "Running Art", description = "런닝 아트 관리 API")
@RestController
@RequestMapping("/api/v1/running-arts")
@RequiredArgsConstructor
public class RunningArtController {

    private final GetRunningArtUseCase getRunningArtUseCase;
    private final DeleteRunningArtUseCase deleteRunningArtUseCase;
    private final UpdateRunningArtUseCase updateRunningArtUseCase;
    private final GetNearbyRunningArtUseCase getNearbyRunningArtUseCase;

    @Operation(
            summary = "내 런닝 아트 목록 조회",
            description = "현재 로그인한 사용자가 생성한 모든 런닝 아트 목록을 최신순으로 조회합니다.",
            security = @SecurityRequirement(name = "jwtAuth"), // JWT 인증이 필요함을 명시
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "조회 성공",
                            useReturnTypeSchema = true
                    ),
                    @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
                    @ApiResponse(responseCode = "500", description = "서버 내부 오류")
            }
    )
    @GetMapping("/me")
    public ResponseEntity<RestApiResponse<Page<RunningArtResponse>>> getByUserId(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @ParameterObject Pageable pageable
    ) {
        PageResult<RunningArtResponse> result = getRunningArtUseCase
                .getRunningArtsByUserId(userId, PageQueryMapper.toPageQuery(pageable))
                .map(RunningArtResponse::toResponse);

        // 응답 JSON 형태를 바꾸지 않기 위해 Spring Data의 Page로 다시 감싼다. (PageQueryMapper 참고)
        return ResponseEntity.ok(RestApiResponse.success(PageQueryMapper.toPage(result, pageable)));
    }

    @Operation(
            summary = "런닝 아트 단건 상세 조회",
            description = "런닝 아트의 고유 ID를 사용하여 상세 정보(제목, 내용, Polyline 경로 등)를 조회합니다.",
            security = @SecurityRequirement(name = "jwtAuth"),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "조회 성공",
                            useReturnTypeSchema = true
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "해당 ID의 런닝 아트를 찾을 수 없음",
                            content = @Content(schema = @Schema(implementation = RestApiResponse.class))
                    ),
                    @ApiResponse(responseCode = "401", description = "인증 실패")
            }
    )
    @GetMapping("/{runningArtId}")
    public ResponseEntity<RestApiResponse<RunningArtResponse>> getById(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "조회할 런닝 아트 ID", example = "1", required = true)
            @PathVariable(name = "runningArtId") Long runningArtId
    ) {
        return ResponseEntity.ok(RestApiResponse.success(
                RunningArtResponse.toResponse(getRunningArtUseCase.getRunningArtById(userId, runningArtId))
        ));
    }

    @Operation(
            summary = "런닝 아트 수정",
            description = "런닝 아트의 제목과 상세 내용을 수정합니다. 작성자 본인만 수정 가능합니다.",
            security = @SecurityRequirement(name = "jwtAuth"),
            responses = {
                    @ApiResponse(
                            responseCode = "204",
                            description = "수정 성공 (데이터 반환 없음)",
                            useReturnTypeSchema = true
                    ),
                    @ApiResponse(responseCode = "400", description = "입력 데이터 유효성 검증 실패"),
                    @ApiResponse(responseCode = "401", description = "인증 실패"),
                    @ApiResponse(responseCode = "403", description = "수정 권한 없음 (작성자 아님)"),
                    @ApiResponse(responseCode = "404", description = "수정할 런닝 아트를 찾을 수 없음")
            }
    )
    @PatchMapping("/{runningArtId}")
    public ResponseEntity<RestApiResponse<Void>> update(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "수정할 런닝 아트 ID", example = "1", required = true)
            @PathVariable(name = "runningArtId") Long runningArtId,
            @RequestBody @Valid UpdateRunningArtRequest request
    ) {
        updateRunningArtUseCase.updateRunningArt(userId, runningArtId, request.toCommand());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).body(RestApiResponse.noContent());
    }

    @Operation(
            summary = "런닝 아트 삭제",
            description = "런닝 아트의 고유 ID를 사용하여 리소스를 삭제합니다. 작성자 본인만 삭제할 수 있습니다.",
            security = @SecurityRequirement(name = "jwtAuth"),
            responses = {
                    @ApiResponse(
                            responseCode = "204",
                            description = "삭제 성공 (데이터 반환 없음)"
                    ),
                    @ApiResponse(responseCode = "401", description = "인증 실패"),
                    @ApiResponse(responseCode = "403", description = "삭제 권한 없음 (작성자 아님)"),
                    @ApiResponse(responseCode = "404", description = "삭제할 런닝 아트를 찾을 수 없음"),
                    @ApiResponse(responseCode = "500", description = "서버 오류")
            }
    )
    @DeleteMapping("/{runningArtId}")
    public ResponseEntity<RestApiResponse<Void>> delete(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "삭제할 런닝 아트 ID", example = "1", required = true)
            @PathVariable(name = "runningArtId") Long runningArtId
    ) {
        deleteRunningArtUseCase.deleteRunningArt(userId, runningArtId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).body(RestApiResponse.noContent());
    }

    @Operation(
            summary = "내 모든 런닝 아트 삭제",
            description = "현재 로그인한 사용자가 작성한 모든 런닝 아트 데이터를 일괄 삭제합니다. 이 작업은 되돌릴 수 없으니 주의하십시오.",
            security = @SecurityRequirement(name = "jwtAuth"),
            responses = {
                    @ApiResponse(
                            responseCode = "204",
                            description = "일괄 삭제 성공 (데이터 반환 없음)"
                    ),
                    @ApiResponse(responseCode = "401", description = "인증 실패"),
                    @ApiResponse(responseCode = "500", description = "서버 내부 오류 (삭제 작업 실패)")
            }
    )
    @DeleteMapping("/me")
    public ResponseEntity<RestApiResponse<Void>> deleteAllByUser(@Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        deleteRunningArtUseCase.deleteAllRunningArtsByUserId(userId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).body(RestApiResponse.noContent());
    }

    @Operation(
            summary = "주변 런닝 아트 반경 검색",
            description = "주어진 중심점 좌표(위도, 경도)를 기준으로 지정된 반경(km) 내에 있는 런닝 아트 목록을 조회합니다. 내부적으로 Redis 공간 인덱스(GEO)를 활용하여 대용량 데이터 환경에서도 고속으로 필터링됩니다.",
            security = @SecurityRequirement(name = "jwtAuth"),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "반경 내 런닝 아트 검색 성공",
                            useReturnTypeSchema = true
                    ),
                    @ApiResponse(responseCode = "400", description = "잘못된 파라미터 (위경도 형식 오류 등)"),
                    @ApiResponse(responseCode = "401", description = "인증 실패"),
                    @ApiResponse(responseCode = "500", description = "서버 내부 오류 (Redis 또는 DB 통신 실패)")
            }
    )
    @RateLimit(target = RateLimit.TargetType.IP, capacity = 10, refillRate = 5)
    @RateLimit(target = RateLimit.TargetType.USER, capacity = 5, refillRate = 3)
    @GetMapping("/nearby")
    public Mono<ResponseEntity<RestApiResponse<List<RunningArtResponse>>>> getNearbyArts(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,

            @Parameter(description = "중심점 위도 (Latitude)", example = "37.5665", required = true)
            @RequestParam("lat") Double lat,

            @Parameter(description = "중심점 경도 (Longitude)", example = "126.9780", required = true)
            @RequestParam("lon") Double lon,

            @Parameter(description = "검색 반경 (단위: km, 기본값: 5.0)", example = "5.0")
            @RequestParam(value = "radius", defaultValue = "5.0") Double radius
    ) {
        return getNearbyRunningArtUseCase.getNearbyArts(lat, lon, radius)
                .map(RunningArtResponse::toResponse)
                .collectList()
                .map(list -> ResponseEntity.ok(RestApiResponse.success(list)));
    }
}