package com.airsense.api.decision;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionSummary {
    private String whatIsHappening;
    private String whyIsItHappening;
    private String whatWillHappenNext;
    private String whatShouldOfficialsDoNow;
    private String whatShouldCitizensDoNow;
}
