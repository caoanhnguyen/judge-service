package com.kma.judgeservice.service;

import com.kma.judgeservice.dto.RunCodeRequest;
import com.kma.judgeservice.dto.RunCodeResponse;

public interface RunCodeService {

    RunCodeResponse executeCustomRun(RunCodeRequest request);
}
