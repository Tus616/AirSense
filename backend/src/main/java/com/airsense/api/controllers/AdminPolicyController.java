package com.airsense.api.controllers;

import com.airsense.api.dto.PolicySimulationRequestDto;
import com.airsense.api.entities.PolicySimulation;
import com.airsense.api.services.PolicySimulationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class AdminPolicyController {

    @Autowired
    private PolicySimulationService policySimulationService;

    @PostMapping("/admin/policy-simulations")
    public ResponseEntity<PolicySimulation> runSimulation(@RequestBody PolicySimulationRequestDto request) {
        String adminId = "admin-user"; // mock admin id
        return ResponseEntity.ok(policySimulationService.runSimulation(request, adminId));
    }

    @GetMapping("/gov/policy-simulations")
    public ResponseEntity<List<PolicySimulation>> getSimulations() {
        return ResponseEntity.ok(policySimulationService.getAllSimulations());
    }
}
