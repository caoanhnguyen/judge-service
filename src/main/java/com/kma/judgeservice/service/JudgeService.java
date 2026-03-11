package com.kma.judgeservice.service;

import com.kma.judgeservice.dto.JudgeResultSdi;
import com.kma.judgeservice.dto.JudgeSdi;

public interface JudgeService {

    JudgeResultSdi judge(JudgeSdi sdi);
}
