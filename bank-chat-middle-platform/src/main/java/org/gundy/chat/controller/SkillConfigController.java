package org.gundy.chat.controller;

import org.gundy.chat.entity.config.SkillConfigResponse;
import org.gundy.chat.service.SkillConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/skills")
public class SkillConfigController {
    private final SkillConfigService skillConfigService;

    public SkillConfigController(SkillConfigService skillConfigService) {
        this.skillConfigService = skillConfigService;
    }

    @GetMapping("/config")
    public ResponseEntity<SkillConfigResponse> getConfig() {
        return ResponseEntity.ok(skillConfigService.getConfig());
    }

    @PutMapping("/config")
    public ResponseEntity<SkillConfigResponse> saveConfig(@RequestBody SkillConfigResponse request) {
        return ResponseEntity.ok(skillConfigService.saveConfig(request));
    }

    @PostMapping("/config/reset")
    public ResponseEntity<SkillConfigResponse> resetConfig() {
        return ResponseEntity.ok(skillConfigService.resetToDefault());
    }
}
