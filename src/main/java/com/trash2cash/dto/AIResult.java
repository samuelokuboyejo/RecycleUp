package com.trash2cash.dto;

import com.trash2cash.users.enums.WasteType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AIResult {
    private boolean aiVerified;
    private String detectedCategory;
    private double confidenceScore;
    private boolean isAuthenticImage;
}
