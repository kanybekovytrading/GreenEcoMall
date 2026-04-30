package greenecomall.entity;

import greenecomall.enums.StageStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tree_positions",
        indexes = {
                @Index(name = "idx_tree_user_level_stage", columnList = "user_id, level, stage"),
                @Index(name = "idx_tree_parent_level_stage", columnList = "parent_id, level, stage")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TreePosition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Integer level;

    @Column(nullable = false)
    private Integer stage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private User parent;

    /** 1 = left, 2 = right */
    private Integer position;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isAccelerator = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StageStatus stageStatus;

    private LocalDateTime completedStageAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
