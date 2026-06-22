package org.dradgo.adapters.persistence;

import java.util.List;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.StepReviewEntity;
import org.dradgo.adapters.persistence.mapper.StepReviewEntityMapper;
import org.dradgo.adapters.persistence.repository.StepReviewRepository;
import org.dradgo.application.review.StepReviewSnapshot;
import org.dradgo.application.review.spi.StepReviewReadPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3d-2 (AC3, Task 7) — JPA-backed {@link StepReviewReadPort}. The latest-verdict query {@code
 * join fetch}es the LAZY FK associations so the mapper can run on this read path without
 * OSIV/transaction (worker / REST read).
 */
@Component
public class StepReviewReadPersistenceAdapter implements StepReviewReadPort {

  private final StepReviewRepository stepReviewRepository;
  private final StepReviewEntityMapper stepReviewEntityMapper;

  public StepReviewReadPersistenceAdapter(
      StepReviewRepository stepReviewRepository, StepReviewEntityMapper stepReviewEntityMapper) {
    this.stepReviewRepository = stepReviewRepository;
    this.stepReviewEntityMapper = stepReviewEntityMapper;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<StepReviewSnapshot> findLatestForRun(String workflowRunPublicId) {
    List<StepReviewEntity> latest =
        stepReviewRepository.findLatestForRun(workflowRunPublicId, PageRequest.of(0, 1));
    return latest.isEmpty()
        ? Optional.empty()
        : Optional.of(stepReviewEntityMapper.toSnapshot(latest.get(0)));
  }
}
