package greenecomall.entity;

import greenecomall.enums.NewsAudience;
import greenecomall.enums.NewsCategory;
import greenecomall.enums.NewsStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "news")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class News {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String excerpt;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NewsCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private NewsStatus status = NewsStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private NewsAudience audience = NewsAudience.ALL;

    // Обложка — либо картинка, либо цвет + иконка
    private String coverImageUrl;
    private String coverColor;  // green | gold | brown | beige
    private String coverIcon;   // LIGHTNING | WALLET | GIFT | CHART | BOX | COMMUNITY | BELL | SHIELD

    @Builder.Default
    private boolean pinned = false;

    private LocalDateTime publishAt;   // null = немедленно при публикации

    @Builder.Default
    private long viewCount = 0;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
