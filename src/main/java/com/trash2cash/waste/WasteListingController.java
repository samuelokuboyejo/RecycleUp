package com.trash2cash.waste;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trash2cash.users.dto.WasteListingRequest;
import com.trash2cash.users.model.UserInfoUserDetails;
import com.trash2cash.users.utils.ListingResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Waste Listing")
@RequestMapping("/listings")
public class WasteListingController {
    private final WasteListingService wasteListingService;

    @PostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Create a new waste listing with AI verification",
            description = """
        Allows an authenticated user to create a new waste listing by uploading an image and metadata.

        **AI Verification Features:**
        - Uses AWS Rekognition to analyze the uploaded image and automatically detect the waste category (Plastic, Metal, Glass).
        - Checks image authenticity using the SightEngine API to ensure it is not AI-generated or fabricated.
        - Performs a reverse image search via SerpAPI (Google Reverse Image) to confirm that the image was not downloaded or reused from the internet.
        - Only listings with authentic (real, unique) images proceed successfully. Reused or AI-generated images trigger an error message:
          > `"The uploaded image appears to be AI-generated or reused (found on the internet). Please upload a real photo of your waste."`
        - The system logs AI confidence scores and category matches to improve accuracy and transparency.

         **Technical Notes:**
        - AI checks run automatically upon upload before the listing is persisted.
        - Images are stored on Cloudinary regardless of reuse status for audit trail purposes.
        - Privileged users or admins can view verification results and confidence metrics.

        🔐 Requires a valid Bearer token in the `Authorization` header.
        """,
            security = { @SecurityRequirement(name = "bearerAuth") }
    )

    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Listing created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid input"),
            @ApiResponse(responseCode = "500", description = "Server error")
    })
    public ResponseEntity<ListingResponse> createListing(
            @AuthenticationPrincipal UserInfoUserDetails principal,
            @Parameter(
                    description = "Waste listing request data (JSON as string). Example: {\"title\":\"Plastic bottles\",\"description\":\"10kg of PET bottles\",\"pickupLocation\":\"Ikeja, Lagos\",\"type\":\"PLASTIC\",\"unit\":1,\"weight\":10,\"contactPhone\":\"08012345678\"}",
                    required = true,
                    schema = @Schema(type = "string", example = "{\"title\":\"Plastic bottles\",\"description\":\"10kg of PET bottles\",\"pickupLocation\":\"Ikeja, Lagos\",\"type\":\"PLASTIC\",\"unit\":1,\"weight\":10,\"contactPhone\":\"08012345678\"}")
            )            @RequestPart("data") String requestJson,

            @Parameter(description = "Image file to upload", required = true)
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        // Convert the JSON string to DTO
        WasteListingRequest request = new ObjectMapper().readValue(requestJson, WasteListingRequest.class);
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String email = principal.getUsername();
        ListingResponse response = wasteListingService.createListing(request, file, email);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }


    @GetMapping
    @Operation(
            summary = "Get all waste listings",
            description = "Retrieves all available waste listings to be displayed on the dashboard"
    )
    public ResponseEntity<List<ListingResponse>> getAllListings() {
        return ResponseEntity.ok(wasteListingService.getAllListings());
    }
    @GetMapping("/open")
    @Operation(
            summary = "Get all OPEN waste listings",
            description = "Retrieves all available OPEN waste listings to be displayed on the dashboard"
    )
    public ResponseEntity<List<ListingResponse>> getAllOpenListings() {
        return ResponseEntity.ok(wasteListingService.getAllOpenListings());
    }

    @GetMapping("/me")
    @Operation(
            summary = "Get all listings created by user",
            description = "Allows an authenticated user to retrieve all waste listings" + "⚠️ Requires a valid Bearer access token in the Authorization header.",
            security = { @SecurityRequirement(name = "bearerAuth")}
    )
    public ResponseEntity<List<ListingResponse>> getMyListings(@AuthenticationPrincipal UserInfoUserDetails principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String email = principal.getUsername();
        return ResponseEntity.ok(wasteListingService.getListingsByUser(email));
    }
}
