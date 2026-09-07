package com.serverbe.adapter.out.persistence.art;

import com.serverbe.domain.model.art.vo.Proficiency;
import com.serverbe.adapter.out.persistence.user.UserEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(
        name = "running_arts",
        indexes = {
                // findByUser_Id / findIdsByUserId / deleteByUserId 가 전부 user_id 로 필터링합니다.
                // 이름을 명시해 두지 않으면 InnoDB 가 FK 제약 이름으로 인덱스를 자동 생성해,
                // 환경마다 이름이 갈립니다. (V5 가 기존 DB 의 이름을 이 표준으로 수렴시켰습니다)
                @Index(name = "idx_running_arts_user", columnList = "user_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class RunningArtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "shape", nullable = false)
    private String shape;

    @Enumerated(EnumType.STRING)
    @Column(name = "proficiency", nullable = false)
    private Proficiency proficiency;

    @Lob
    @Column(name = "gpx", columnDefinition = "LONGTEXT")
    private String gpx;

    @Column(name = "start_lat", nullable = false)
    private Double startLat;

    // 반경 검색을 위한 시작점 경도(Longitude)
    @Column(name = "start_lon", nullable = false)
    private Double startLon;

    @Column(name = "distance", nullable = false)
    private Double distance;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    // 연관관계 설정 (주인)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_running_arts_user"))
    private UserEntity user;

    @Builder
    public RunningArtEntity(
            String title,
            String content,
            String shape,
            Proficiency proficiency,
            String gpx,
            Double distance,
            UserEntity user,
            Double startLat,
            Double startLon
    ) {
        this.title = title;
        this.content = content;
        this.shape = shape;
        this.proficiency = proficiency;
        this.gpx = gpx;
        this.user = user;
        this.distance = distance;
        this.startLat = startLat;
        this.startLon = startLon;
    }

    public void assignUser(UserEntity user) {
        this.user = user;
    }

    public void updateMetadata(String title, String content) {
        if (title != null) {
            this.title = title;
        }
        if (content != null) {
            this.content = content;
        }
    }
}