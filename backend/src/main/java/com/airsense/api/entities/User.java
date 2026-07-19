package com.airsense.api.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "users")
public class User {
    @Id
    private String id;
    
    @org.springframework.data.mongodb.core.index.Indexed(unique = true)
    private String email;
    @JsonIgnore
    private String password;
    private String name;
    private String role;
    
    // Citizen Profile fields
    private String wardId;
    private String cityId;
    private String selectedLanguage; // en, hi, kn, ta
    private Boolean vulnerable;
    private java.util.List<String> conditions; // asthma, COPD, elderly, child, pregnancy, outdoorWorker
    private java.util.List<String> channels; // inApp, publicDisplay, ivrScript, sms, email, push
}
