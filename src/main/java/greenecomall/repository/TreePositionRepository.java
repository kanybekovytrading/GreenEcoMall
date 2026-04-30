package greenecomall.repository;

import greenecomall.entity.TreePosition;
import greenecomall.entity.User;
import greenecomall.enums.StageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TreePositionRepository extends JpaRepository<TreePosition, UUID> {

    Optional<TreePosition> findByUserAndLevelAndStage(User user, int level, int stage);

    List<TreePosition> findByParentAndLevelAndStage(User parent, int level, int stage);

    int countByParentAndLevelAndStage(User parent, int level, int stage);

    @Query("SELECT tp FROM TreePosition tp WHERE tp.user = :user AND tp.level = :level AND tp.stage = 1 AND tp.stageStatus = :status")
    Optional<TreePosition> findStage1Position(@Param("user") User user,
                                               @Param("level") int level,
                                               @Param("status") StageStatus status);

    @Query("SELECT COUNT(tp) FROM TreePosition tp WHERE tp.parent.id = :parentId AND tp.level = :level AND tp.stage = :stage")
    int countChildrenByParentId(@Param("parentId") UUID parentId,
                                @Param("level") int level,
                                @Param("stage") int stage);

    @Query("SELECT tp FROM TreePosition tp WHERE tp.user.id = :userId AND tp.level = :level AND tp.stage = :stage")
    Optional<TreePosition> findByUserIdAndLevelAndStage(@Param("userId") UUID userId,
                                                         @Param("level") int level,
                                                         @Param("stage") int stage);
}
