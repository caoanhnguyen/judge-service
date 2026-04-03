package com.kma.judgeservice.service;

import com.kma.judgeservice.dto.requests.JudgeResultSdi;
import com.kma.judgeservice.dto.requests.JudgeSdi;

public interface JudgeService {

    JudgeResultSdi judge(JudgeSdi sdi);
}
