package com.airsense.api.dto;

import com.airsense.api.entities.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CitizenProfileDto {
    private String id;
    private String email;
    private String name;
    private String role;
    private String wardId;
    private String cityId;
    private String selectedLanguage;
    private Boolean vulnerable;
    private List<String> conditions;
    private List<String> channels;

    public static CitizenProfileDto from(User user) {
        return CitizenProfileDto.builder()
                .id(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .role(user.getRole())
                .wardId(user.getWardId())
                .cityId(user.getCityId())
                .selectedLanguage(user.getSelectedLanguage())
                .vulnerable(user.getVulnerable())
                .conditions(user.getConditions() != null ? user.getConditions() : List.of())
                .channels(user.getChannels() != null ? user.getChannels() : List.of("inApp"))
                .build();
    }
}
