package com.groupdeal.dealservice.mapper;

import com.groupdeal.dealservice.domain.Deal;
import com.groupdeal.dealservice.web.dto.DealResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface DealMapper {
    DealResponse toDealResponse(Deal deal);
}
