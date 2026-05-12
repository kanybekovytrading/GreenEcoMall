package greenecomall.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "news_media")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewsMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "news_id", nullable = false)
    private News news;

    @Column(nullable = false)
    private String objectKey;

    @Builder.Default
    private int sortOrder = 0;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
