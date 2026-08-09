package com.groupdeal.dealservice.repository;

import com.groupdeal.dealservice.domain.DealSlotRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DealSlotRequestRepository extends JpaRepository<DealSlotRequest, UUID> {

    // TODO (step 4): reserve-slot/release-slot check this before doing any real work.
    Optional<DealSlotRequest> findByRequestId(UUID requestId);
}
