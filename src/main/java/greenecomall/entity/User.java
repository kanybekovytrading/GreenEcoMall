package greenecomall.entity;

import greenecomall.enums.AccountStatus;
import greenecomall.enums.RegistrationPlan;
import greenecomall.enums.Role;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false, unique = true)
    private String phone;

    @Column(nullable = false, unique = true)
    private String passportNumber;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false, unique = true, length = 14)
    private String referralCode;

    // Finik номер для вывода средств (сохраняется в профиле)
    private String finikPhone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inviter_id")
    private User inviter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    private RegistrationPlan registrationPlan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus accountStatus;

    @Column(nullable = false)
    private Integer currentLevel;

    @Column(nullable = false)
    private Integer currentStage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fixed_partner_left_id")
    private User fixedPartnerLeft;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fixed_partner_right_id")
    private User fixedPartnerRight;

    @Column(nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime activatedAt;
}
