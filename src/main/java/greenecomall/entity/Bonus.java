package greenecomall.entity;

import greenecomall.enums.BonusStatus;
import greenecomall.enums.BonusType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "bonuses",
        indexes = {
                @Index(name = "idx_bonus_user_id", columnList = "user_id"),
                @Index(name = "idx_bonus_from_user_status", columnList = "from_user_id, status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Bonus {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_user_id")
    private User fromUser;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BonusType type;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BonusStatus status;

    private Integer level;
    private Integer stage;

    private String description;

    private LocalDateTime confirmedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
