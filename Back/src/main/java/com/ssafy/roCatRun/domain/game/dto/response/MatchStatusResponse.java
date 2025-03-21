package com.ssafy.roCatRun.domain.game.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class MatchStatusResponse {
    private String roomId;
    private int currentPlayers;
    private int maxPlayers;
}
