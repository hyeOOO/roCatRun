package com.ssafy.roCatRun.domain.game.service;

import com.corundumstudio.socketio.SocketIOServer;
import com.ssafy.roCatRun.domain.game.dto.request.CreateRoomRequest;
import com.ssafy.roCatRun.domain.game.dto.request.MatchRequest;
import com.ssafy.roCatRun.domain.game.dto.request.PlayerRunningResultRequest;
import com.ssafy.roCatRun.domain.game.dto.response.*;
import com.ssafy.roCatRun.domain.game.entity.raid.GameRoom;
import com.ssafy.roCatRun.domain.game.entity.raid.GameStatus;
import com.ssafy.roCatRun.domain.game.entity.raid.Player;
import com.ssafy.roCatRun.domain.game.service.manager.GameRoomManager;
import com.ssafy.roCatRun.domain.game.entity.raid.RunningData;
import com.ssafy.roCatRun.domain.game.service.manager.GameTimerManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * GameService.java
 * 게임 비즈니스 로직(게임 매칭 및 방 관리 로직을 처리하는 서비스 클래스)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GameService implements GameTimerManager.GameTimeoutListener  {
    private static final int INVITE_CODE_LENGTH = 6;
    private final Map<String, String> inviteCodes = new ConcurrentHashMap<>(); // 초대코드 - 방코드 매칭
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final SocketIOServer server;

    private final GameRoomManager gameRoomManager;
    private final GameTimerManager gameTimerManager;

    // 게임 종료 후 결과 데이터를 임시 저장할 Map
    private final Map<String, Map<String, PlayerRunningResultRequest>> gameResults = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        gameTimerManager.setTimeoutListener(this);
    }

    @Override
    public void onTimeout(GameRoom room) {
        handleGameOver(room);
    }
    /**
     * 랜덤 매칭 처리
     * 조건에 맞는 방이 있으면 입장시키고, 없으면 새로운 방 생성
     * @param userId 유저 식별자
     * @param request 매칭 요청 정보 (보스 레벨, 최대 인원 등)
     * @return 입장한 또는 생성된 게임방
     */
    public GameRoom findOrCreateRandomMatch(String userId, MatchRequest request) {
        // 1. 먼저 적합한 방이 있는지 찾기
        Optional<GameRoom> existingRoom = gameRoomManager.findRandomRoom(request.getBossLevel(), request.getMaxPlayers());

        // 기존 방이 있으면 해당 방에 입장
        if (existingRoom.isPresent()) {
            GameRoom room = existingRoom.get();
            handlePlayerJoin(room, userId); // handlePlayerJoin 사용
            return room;
        }

        // 2. 없으면 새로운 방 생성
        GameRoom newRoom = new GameRoom(
                UUID.randomUUID().toString(),
                request.getBossLevel(),
                request.getMaxPlayers(),
                true
        );

        gameRoomManager.addRoom(newRoom);
        handlePlayerJoin(newRoom, userId); // handlePlayerJoin 사용
        return newRoom;
    }

    /**
     * 유저 연결 종료 처리
     * 게임방에서 해당 유저를 제거하고, 필요시 방 삭제
     * @param userId 유저 식별자
     */
    public void handleUserDisconnect(String userId, GameRoom room) {
            // 방에서 유저 제거
            room.getPlayers().removeIf(player -> player.getId().equals(userId));
            if (room.getPlayers().isEmpty()) {
                gameRoomManager.removeRoom(room.getId());
            } else {
                gameRoomManager.updateRoom(room);
            }
    }

    /**
     * 방 생성
     * @param userId 유저 식별자
     * @param request 방 정보(보스 레벨, 참여 인원)
     * @return
     */
    public GameRoom createPrivateRoom(String userId, CreateRoomRequest request){
        // 새로운 방 생성
        GameRoom newRoom = new GameRoom(
                UUID.randomUUID().toString(),
                request.getBossLevel(),
                request.getMaxPlayers(),
                false
        );

        // 초대 코드 생성
        String inviteCode = generateInviteCode();
        inviteCodes.put(inviteCode, newRoom.getId());
        newRoom.setInviteCode(inviteCode);

        gameRoomManager.addRoom(newRoom);
        handlePlayerJoin(newRoom, userId); // handlePlayerJoin 사용
        return newRoom;
    }

    /**
     * 초대코드 생성
     */
    private String generateInviteCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder code;
        do{
            code = new StringBuilder();
            for(int i=0; i<INVITE_CODE_LENGTH; i++){
                code.append(chars.charAt(new Random().nextInt(chars.length())));
            }
        }while(inviteCodes.containsKey(code.toString())); // 중복체크

        return code.toString();
    }

    /**
     * 초대코드로 방 참여
     * @param userId 유저 식별자
     * @param inviteCode 초대코드
     * @return
     */
    public GameRoom joinRoomByInviteCode(String userId, String inviteCode){
        String roomId = inviteCodes.get(inviteCode);
        if(roomId==null){
            throw new IllegalArgumentException("Invalid invite code");
        }

        GameRoom room = gameRoomManager.getRoom(roomId)
                .orElseThrow(()->new IllegalArgumentException("Room not found"));

        handlePlayerJoin(room, userId);
        return room;
    }

    /**
     * 방에 유저 추가
     * @param room 방 정보
     * @param userId 유저 식별자
     */
    public void handlePlayerJoin(GameRoom room, String userId) {
        // 게임 중이면 입장 불가
        if (room.getStatus() != GameStatus.WAITING) {
            throw new IllegalStateException("Game is already in progress");
        }

        // 인원 초과 체크
        if (room.getPlayers().size() >= room.getMaxPlayers()) {
            throw new IllegalStateException("Room is full");
        }

        // 플레이어 추가
        Player player = new Player(userId);
        room.addPlayer(player);

        gameRoomManager.updateRoom(room);

    }

    /**
     * 게임 시작 조건 체크 및 시작
     */
    public void checkAndStartGame(GameRoom room) {
        // 최대 인원 도달 시 게임 시작 카운트다운 시작
        if (room.getPlayers().size() == room.getMaxPlayers()) {
            startGameCountdown(room);
        }
    }

    /**
     * 인원이 다 모였을 때 카운트다운 후 게임 시작
     * @param room 방 정보
     */
    public void startGameCountdown(GameRoom room){
        room.setStatus(GameStatus.READY);
        gameRoomManager.updateRoom(room);

        // 모든 플레이어에게 READY 상태 알림
        server.getRoomOperations(room.getId()).sendEvent("gameReady", new GameReadyResponse(
                "게임이 곧 시작됩니다!",
                room.getPlayers()
        ));

                room.setStatus(GameStatus.PLAYING);
                room.startGame();
                gameRoomManager.updateRoom(room);

                //게임 타이머 시작
                gameTimerManager.startGameTimer(room);

                server.getRoomOperations(room.getId()).sendEvent("gameStart"
                        , GameStartResponse.of(
                        room.getId(),
                        "게임이 시작되었습니다!",
                        room.getBossHealth(),
                        room.getPlayers()
                ));
    }

    /**
     * 유저 러닝 정보 실시간 업데이트 및 브로드캐스트
     * @param userId 유저 식별자
     * @param newData 실시간 유저 러닝 정보
     */
    public void handleRunningDataUpdate(String userId, RunningData newData) {
        GameRoom room = gameRoomManager.findRoomByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Room not found"));

        if (room.getStatus() != GameStatus.PLAYING) {
            return;
        }

        // 유저ID로 유저 상세 정보 가져오기
        Player player = room.getPlayerById(userId);
        // 유저 상세 정보 중 러닝 데이터 갱신
        player.updateRunningData(newData);
        // 레이드 뛰는 사람들에게 공유하기 위한 갱신
        gameRoomManager.updateRoom(room);

        broadcastPlayerUpdate(room, player);
    }

    /**
     * 유저의 아이템 사용
     * @param userId 유저 식별자
     */
    public void handleItemUse(String userId) {
        GameRoom room = gameRoomManager.findRoomByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Room not found"));

        Player player = room.getPlayerById(userId);
        // 아이템 사용
        player.useItem();
        // 보스 피격
        room.applyDamage(GameRoom.ITEM_DAMAGE);

        // 피버타임 체크 및 처리
        handleFeverTimeCheck(room);

        gameRoomManager.updateRoom(room);

        // 게임 종료 체크
        if (room.isGameFinished()) {
            handleGameOver(room);
        } else {
            broadcastGameStatus(room, player);
        }
    }

    /**
     * 방 정보를 바탕으로 피버타임 체크
     * @param room 방 정보
     */
    private void  handleFeverTimeCheck(GameRoom room) {
        if (room.checkFeverCondition()) {
            room.startFeverTime();
            broadcastFeverTimeStart(room);

            // 피버타임 종료 스케줄링
            scheduler.schedule(() -> {
                if (room.getStatus() == GameStatus.PLAYING) {
                    room.endFeverTime();
                    gameRoomManager.updateRoom(room);
                    broadcastFeverTimeEnd(room);
                }
            }, GameRoom.FEVER_TIME_DURATION, TimeUnit.SECONDS);
        }
    }
    /**
     * 게임 종료 처리
     * @param room 방 정보
     */
    public void handleGameOver(GameRoom room) {
        log.info("[Game Over] Room: {}, Players: {}, Boss Health: {}, Clear Status: {}",
                room.getId(),
                room.getPlayers().size(),
                room.getBossHealth(),
                room.getBossHealth() <= 0 ? "Success" : "Failed"
        );

        room.setStatus(GameStatus.FINISHED);
        gameRoomManager.updateRoom(room);

        gameResults.put(room.getId(), new ConcurrentHashMap<>());

        // 게임 종료 알림만 전송
        server.getRoomOperations(room.getId()).sendEvent("gameOver",
                new GameOverResponse(true, "게임이 종료되었습니다."));
    }


//    /**
//     * 게임 종료 처리
//     * @param room 방 정보
//     */
//    public void handleGameOver(GameRoom room) {
//        room.setStatus(GameStatus.FINISHED);
//        GameResultResponse result = createGameResult(room);
//        server.getRoomOperations(room.getId()).sendEvent("gameOver", result);
//        // 방에서 제외 및 방 제거 처리
//        for(Player player : room.getPlayers()){
//            handleUserDisconnect(player.getId(), room);
//        }
//    }

    /**
     * 유저에게서 받은 러닝 결과 처리
     * @param userId 유저식별자
     * @param resultData 러닝 결과 데이터
     */
    public void handleRunningResult(String userId, PlayerRunningResultRequest resultData) {
        GameRoom room = gameRoomManager.findRoomByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Room not found"));

        if (room.getStatus() != GameStatus.FINISHED) {
            log.warn("[Running Result] Invalid request - Game not finished. Room: {}, User: {}",
                    room.getId(), userId);
            throw new IllegalStateException("게임이 아직 끝나지 않았습니다.");
        }

        // 결과 데이터 저장
        Map<String, PlayerRunningResultRequest> roomResults = gameResults.get(room.getId());
        roomResults.put(userId, resultData);

        log.info("[Running Result] Received data from User: {}, Room: {}, Current submissions: {}/{}",
                userId, room.getId(), roomResults.size(), room.getPlayers().size());

        // 모든 플레이어의 결과가 수집되었는지 확인
        if (roomResults.size() == room.getPlayers().size()) {
            // 최종 결과 생성 및 전송
            broadcastFinalResult(room, roomResults);

            // 임시 저장된 결과 데이터 삭제
            gameResults.remove(room.getId());

            // 방 정리
            cleanupRoom(room);
        }
    }

    private void broadcastFinalResult(GameRoom room, Map<String, PlayerRunningResultRequest> results) {
        List<GameResultResponse.PlayerResult> playerResults = room.getPlayers().stream()
                .map(player -> {
                    PlayerRunningResultRequest result = results.get(player.getId());
                    return new GameResultResponse.PlayerResult(
                            player.getId(),
                            result.getRunningTime(),
                            result.getTotalDistance(),
                            result.getPaceAvg(),
                            result.getHeartRateAvg(),
                            result.getCadenceAvg(),
                            0,
                            player.getUsedItemCount()
                    );
                })
                .sorted((p1, p2) -> Double.compare(p2.getTotalDistance(), p1.getTotalDistance()))
                .collect(Collectors.toList());

        GameResultResponse finalResult = new GameResultResponse(
                room.getBossHealth() <= 0,
                playerResults
        );

        server.getRoomOperations(room.getId()).sendEvent("gameResult", finalResult);
    }

    private void cleanupRoom(GameRoom room) {
        for (Player player : room.getPlayers()) {
            handleUserDisconnect(player.getId(), room);
        }
    }

    /**
     * 레이드 방 내 유저 정보 실시간 알림
     * @param room 방 정보
     * @param player 유저 정보
     */
    private void broadcastPlayerUpdate(GameRoom room, Player player) {
        RunningDataUpdateResponse response = new RunningDataUpdateResponse(
                player.getId(),
                player.getRunningData().getDistance(),
                player.getUsedItemCount()
        );
        server.getRoomOperations(room.getId()).sendEvent("playerDataUpdated", response);
    }

    /**
     * 유저가 아이템 사용 시, 게임 상태에 대해 알림
     * @param room 방 정보
     * @param player 플레이어 정보
     */
    private void broadcastGameStatus(GameRoom room, Player player) {
        GameStatusResponse response = new GameStatusResponse(
                room.getBossHealth(),
                room.isFeverTimeActive(),
                player.getId(),
                player.getUsedItemCount()
        );
        server.getRoomOperations(room.getId()).sendEvent("gameStatusUpdated", response);
    }

    /**
     * 피버타임 시작 시, 피버 지속 시간 알림
     * @param room 방 정보
     */
    private void broadcastFeverTimeStart(GameRoom room) {
        server.getRoomOperations(room.getId()).sendEvent("feverTimeStarted",
                new FeverTimeStartedResponse(true, GameRoom.FEVER_TIME_DURATION));
    }

    /**
     * 피버타임 종료 알림
     * @param room
     */
    private void broadcastFeverTimeEnd(GameRoom room) {
        server.getRoomOperations(room.getId()).sendEvent("feverTimeEnded",
                new FeverTimeEndedResponse("피버타임이 종료되었습니다"));
    }


}
