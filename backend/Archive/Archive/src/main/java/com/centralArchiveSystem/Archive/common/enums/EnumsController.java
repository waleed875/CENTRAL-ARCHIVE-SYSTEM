package com.centralArchiveSystem.Archive.common.enums;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The constrained value sets of the API, for clients that cannot read the enums.
 *
 * <p>Readable by any authenticated user, like the reference data it complements: a user who cannot
 * open the organisations screen does not need the organisation types either. Nothing here is
 * sensitive, and it is all already visible in {@code GET /v3/api-docs} except that the docs are
 * disabled in the production profile.
 */
@RestController
@RequestMapping("/api/v1/enums")
@Tag(name = "Enums", description = "Constrained value sets accepted by the API")
public class EnumsController {

    private final EnumCatalog enumCatalog;

    public EnumsController(EnumCatalog enumCatalog) {
        this.enumCatalog = enumCatalog;
    }

    @GetMapping
    @Operation(summary = "List every set of values the API restricts a field to",
            description = "Returns an object whose keys are field sets and whose values are the accepted "
                    + "values. Empty sets are not returned. A field absent from the response is free text.")
    public ResponseEntity<ApiResponse<Map<String, java.util.List<String>>>> list() {
        return ResponseEntity.ok(ApiResponse.success(enumCatalog.values()));
    }
}
