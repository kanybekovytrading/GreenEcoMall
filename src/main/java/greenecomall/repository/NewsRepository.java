package greenecomall.repository;

import greenecomall.entity.News;
import greenecomall.enums.NewsAudience;
import greenecomall.enums.NewsCategory;
import greenecomall.enums.NewsStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface NewsRepository extends JpaRepository<News, UUID> {

    // Клиент: опубликованные новости для данной аудитории
    @Query("SELECT n FROM News n WHERE n.status = 'PUBLISHED' AND n.audience IN :audiences " +
           "ORDER BY n.pinned DESC, n.publishAt DESC")
    Page<News> findPublishedForAudiences(@Param("audiences") List<NewsAudience> audiences, Pageable pageable);

    @Query("SELECT n FROM News n WHERE n.status = 'PUBLISHED' AND n.audience IN :audiences " +
           "AND n.category = :category ORDER BY n.pinned DESC, n.publishAt DESC")
    Page<News> findPublishedForAudiencesByCategory(@Param("audiences") List<NewsAudience> audiences,
                                                    @Param("category") NewsCategory category,
                                                    Pageable pageable);

    // Кол-во непрочитанных (для бейджа в меню — упрощённо: опубликованные за последние 7 дней)
    @Query("SELECT COUNT(n) FROM News n WHERE n.status = 'PUBLISHED' AND n.audience IN :audiences " +
           "AND n.publishAt >= :since")
    long countRecentForAudiences(@Param("audiences") List<NewsAudience> audiences,
                                  @Param("since") LocalDateTime since);

    // Админ: без поиска
    @Query("SELECT n FROM News n WHERE (:status IS NULL OR n.status = :status) " +
           "ORDER BY n.pinned DESC, n.createdAt DESC")
    Page<News> findByStatusAdmin(@Param("status") NewsStatus status, Pageable pageable);

    // Админ: с поиском (вызывается только когда search != null)
    @Query("SELECT n FROM News n WHERE (:status IS NULL OR n.status = :status) " +
           "AND (LOWER(n.title) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(n.excerpt) LIKE LOWER(CONCAT('%', :search, '%'))) " +
           "ORDER BY n.pinned DESC, n.createdAt DESC")
    Page<News> findByStatusAndSearch(@Param("status") NewsStatus status,
                                      @Param("search") String search,
                                      Pageable pageable);

    long countByStatus(NewsStatus status);

    @Query("SELECT COALESCE(SUM(n.viewCount), 0) FROM News n WHERE n.publishAt >= :from")
    long sumViewsSince(@Param("from") LocalDateTime from);

    // Авто-публикация запланированных
    @Query("SELECT n FROM News n WHERE n.status = 'SCHEDULED' AND n.publishAt <= :now")
    List<News> findDueScheduled(@Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE News n SET n.viewCount = n.viewCount + 1 WHERE n.id = :id")
    void incrementViewCount(@Param("id") UUID id);
}
