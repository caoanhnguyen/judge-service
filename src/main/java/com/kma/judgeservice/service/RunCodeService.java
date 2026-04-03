package com.kma.judgeservice.service;

import com.kma.judgeservice.dto.requests.RunCodeRequest;
import com.kma.judgeservice.dto.responses.RunCodeResponse;

public interface RunCodeService {

    RunCodeResponse executeCustomRun(RunCodeRequest request);
}
