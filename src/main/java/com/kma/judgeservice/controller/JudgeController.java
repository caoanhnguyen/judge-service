package com.kma.judgeservice.controller;

import com.kma.judgeservice.dto.JudgeSdi;
import com.kma.judgeservice.service.JudgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/judge")
@RequiredArgsConstructor
public class JudgeController {

    private final JudgeService judgeService;

    @PostMapping("/submit")
    public String submitCode(@RequestBody JudgeSdi sdi) {
        return judgeService.judge(sdi);
    }
}