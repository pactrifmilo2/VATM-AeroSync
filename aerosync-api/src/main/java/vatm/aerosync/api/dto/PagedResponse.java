package vatm.aerosync.api.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious,
        String snapshotId
) {
    public PagedResponse(List<T> content, int page, int size, long totalElements,
                         int totalPages, boolean hasNext, boolean hasPrevious) {
        this(content, page, size, totalElements, totalPages, hasNext, hasPrevious, null);
    }

    public static <T> PagedResponse<T> from(Page<T> result) {
        return new PagedResponse<>(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext(),
                result.hasPrevious(), null);
    }
}
