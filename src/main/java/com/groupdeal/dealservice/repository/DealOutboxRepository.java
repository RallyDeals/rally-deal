package com.groupdeal.dealservice.repository;

import com.groupdeal.dealservice.domain.DealOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DealOutboxRepository extends JpaRepository<DealOutbox, Long> {

    // TODO (step 5): used by the relay poller.
    List<DealOutbox> findTop50ByPublishedAtIsNullOrderByIdAsc();
}
