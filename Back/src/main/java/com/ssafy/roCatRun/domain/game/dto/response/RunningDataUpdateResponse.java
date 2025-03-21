package com.ssafy.roCatRun.domain.game.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RunningDataUpdateResponse {
    private String userId;
    private String nickName;
    private double distance;
    private int itemUseCount;
}
