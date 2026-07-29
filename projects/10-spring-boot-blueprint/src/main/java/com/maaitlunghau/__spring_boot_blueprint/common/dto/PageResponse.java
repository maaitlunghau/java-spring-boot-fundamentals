package com.maaitlunghau.__spring_boot_blueprint.common.dto;

import java.util.List;

public record PageResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean last
) {
    public static <T> PageResponse<T> from(org.springframework.data.domain.Page<T> page) {
        return new PageResponse<>(
                page.getContent(), 
                page.getNumber(), 
                page.getSize(),
                page.getTotalElements(), 
                page.getTotalPages(), 
                page.isLast()
        );
    }
}       
