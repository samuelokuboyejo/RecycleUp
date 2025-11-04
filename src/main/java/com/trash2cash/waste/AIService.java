package com.trash2cash.waste;

import com.trash2cash.dto.AIResult;
import com.trash2cash.users.enums.WasteType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.DetectLabelsRequest;
import software.amazon.awssdk.services.rekognition.model.DetectLabelsResponse;
import software.amazon.awssdk.services.rekognition.model.Image;
import software.amazon.awssdk.services.rekognition.model.Label;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AIService {

    @Value("${sightengine.api.user}")
    private String sightEngineUser;

    @Value("${sightengine.api.secret}")
    private String sightEngineSecret;


    @Value("${serpapi.api.key}")
    private String serpApiKey;

    private final RekognitionClient rekognitionClient;

    private final WebClient serpApiClient = WebClient.builder()
            .baseUrl("https://serpapi.com")
            .build();

    public AIResult verifyImage(String imageUrl, String declaredType) {
        byte[] imageBytes = downloadImageBytes(imageUrl);

        DetectLabelsRequest request = DetectLabelsRequest.builder()
                .image(Image.builder().bytes(SdkBytes.fromByteArray(imageBytes)).build())
                .maxLabels(5)
                .minConfidence(70F)
                .build();

        DetectLabelsResponse result = rekognitionClient.detectLabels(request);

        Label topLabel = result.labels().stream()
                .max(Comparator.comparing(Label::confidence))
                .orElse(null);

        if (topLabel == null) {
            return AIResult.builder()
                    .aiVerified(false)
                    .detectedCategory("UNKNOWN")
                    .confidenceScore(0)
                    .isAuthenticImage(false)
                    .build();
        }

        double confidence = topLabel.confidence();
        WasteType detectedType = mapLabelToWasteType(topLabel.name());

        // Check image authenticity (Sightengine)
        boolean authentic = checkAuthenticity(imageUrl);

        // Check if the image appears elsewhere on the web (reverse image search)
        boolean reused = isImageReused(imageUrl);

        // Decide aiVerified
        double confidenceThreshold = 75.0;
        boolean categoryMatch = isCategoryMatch(detectedType, declaredType);

// Image is authentic only if it's not AI-generated and not reused
        boolean authenticImage = authentic && !reused;

        boolean verified = categoryMatch && confidence >= confidenceThreshold;

        log.info("AI Verification summary → Authentic: {}, Reused: {}, Match: {}, Confidence: {}, Verified: {}",
                authenticImage, reused, categoryMatch, confidence, verified);

        return AIResult.builder()
                .aiVerified(verified)
                .detectedCategory(detectedType.name())
                .confidenceScore(confidence)
                .isAuthenticImage(authenticImage)
                .build();
    }

    private byte[] downloadImageBytes(String imageUrl) {
        try (InputStream in = new URL(imageUrl).openStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to download image: " + imageUrl, e);
        }
    }


    private boolean checkAuthenticity(String imageUrl) {
        try {
            String apiUser = sightEngineUser;
            String apiSecret = sightEngineSecret;
            String endpoint = "https://api.sightengine.com/1.0/check.json";

            log.info("Checking authenticity for: {}", imageUrl);
            log.info("Sightengine endpoint: {}", endpoint);

            String query = String.format(
                    "url=%s&models=genai&api_user=%s&api_secret=%s",
                    URLEncoder.encode(imageUrl, StandardCharsets.UTF_8),
                    URLEncoder.encode(apiUser, StandardCharsets.UTF_8),
                    URLEncoder.encode(apiSecret, StandardCharsets.UTF_8)
            );

            URL url = new URL(endpoint + "?" + query);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                log.error("Sightengine API returned status: {}", responseCode);
                try (BufferedReader err = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                    String errorResponse = err.lines().collect(Collectors.joining());
                    log.error("Error response body: {}", errorResponse);
                }
                return false;
            }

            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String response = in.lines().collect(Collectors.joining());
                log.info("Sightengine Response: {}", response);

                JSONObject json = new JSONObject(response);
                if (!json.has("type") || !json.getJSONObject("type").has("ai_generated")) {
                    log.error("No AI-generated score found in response.");
                    return false;
                }

                double aiGeneratedScore = json.getJSONObject("type").optDouble("ai_generated", 0.0);
                log.info("AI-generated score: {}", aiGeneratedScore);

                boolean isAuthentic = aiGeneratedScore < 0.5;
                log.info("Image authenticity result: {}", isAuthentic ? "Authentic (real image)" : "AI-generated");

                return isAuthentic;
            }

        } catch (Exception e) {
            log.error("Error during authenticity check: {}", e.getMessage(), e);
            return false;
        }
    }


    private boolean isImageReused(String imageUrl) {
        try {
            Map<String, Object> response = serpApiClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search")
                            .queryParam("engine", "google_reverse_image")
                            .queryParam("image_url", imageUrl)
                            .queryParam("api_key", serpApiKey)
                            .queryParam("no_cache", "true")
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) {
                log.warn("Reverse image search returned null response.");
                return false;
            }

            Object obj = response.get("image_results");
            if (!(obj instanceof List<?> results)) {
                log.warn("Unexpected format from SerpAPI: no image_results list.");
                return false;
            }

            if (results.isEmpty()) {
                log.info("No visually similar images found online → not reused.");
                return false;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> resultList = (List<Map<String, Object>>) results;

            List<String> matchedUrls = resultList.stream()
                    .map(r -> (String) r.get("link"))
                    .filter(Objects::nonNull)
                    .toList();

            // Only flag reuse for well-known stock/photo-sharing sites
            List<String> knownImageHosts = List.of(
                    "pinterest", "shutterstock", "istock", "gettyimages",
                    "unsplash", "pexels", "pixabay", "alamy", "wikimedia"
            );

            boolean reused = matchedUrls.stream().anyMatch(url ->
                    knownImageHosts.stream().anyMatch(url::contains)
            );

            if (imageUrl.contains("res.cloudinary.com")) {
                reused = false;
            }

            log.info("Reverse image matches found: {}", matchedUrls);
            log.info("Reused (stock/image-host matches found): {}", reused);

            return reused;

        } catch (Exception e) {
            log.error("Reverse image search failed: {}", e.getMessage(), e);
            return false;
        }
    }


    private WasteType mapLabelToWasteType(String labelName) {
        String lower = labelName.toLowerCase();
        if (lower.contains("metal") || lower.contains("spoke") || lower.contains("steel")
                || lower.contains("iron") || lower.contains("aluminum") || lower.contains("aluminium") ||
                lower.contains("can") || lower.contains("foil") || lower.contains("scrap") || lower.contains("tin") ||
                lower.contains("screw") || lower.contains("nail") || lower.contains("bolt") ||
                lower.contains("copper") || lower.contains("zinc") || lower.contains("bronze"))
            return WasteType.METAL;

        if (lower.contains("plastic") || lower.contains("bottle") || lower.contains("container")
                ||  lower.contains("polyethylene") || lower.contains("wrapper") || lower.contains("bag") ||
                lower.contains("cap") || lower.contains("lid") || lower.contains("pet") ||
                lower.contains("packaging") || lower.contains("nylon") || lower.contains("bucket") ||
                lower.contains("jug") || lower.contains("dispenser") || lower.contains("straw") )
            return WasteType.PLASTIC;

        if (lower.contains("glass") || lower.contains("jar") || lower.contains("cup") ||
                lower.contains("wine") || lower.contains("beer") || lower.contains("mug") ||
                lower.contains("vase")  || lower.contains("mirror") ||
                lower.contains("window") || lower.contains("goblet") || lower.contains("flask"))
            return WasteType.GLASS;
        return WasteType.PLASTIC;
    }

    private boolean isCategoryMatch(WasteType detectedType, String declaredType) {
        return detectedType.name().equalsIgnoreCase(declaredType);
    }
}
