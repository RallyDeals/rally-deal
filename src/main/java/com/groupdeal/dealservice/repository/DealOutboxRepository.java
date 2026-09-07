package com.groupdeal.dealservice.repository;

import com.groupdeal.dealservice.domain.DealOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DealOutboxRepository extends JpaRepository<DealOutbox, UUID> {

    List<DealOutbox> findTop50ByPublishedAtIsNullOrderByIdAsc();
}
