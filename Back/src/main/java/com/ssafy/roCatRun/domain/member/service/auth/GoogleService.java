package com.ssafy.roCatRun.domain.member.service.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.roCatRun.domain.member.dto.response.LoginResponse;
import com.ssafy.roCatRun.domain.member.dto.token.AuthTokens;
import com.ssafy.roCatRun.domain.member.entity.Member;
import com.ssafy.roCatRun.domain.member.repository.MemberRepository;
import com.ssafy.roCatRun.domain.member.repository.RefreshTokenRedisRepository;
import com.ssafy.roCatRun.global.exception.TokenRefreshException;
import com.ssafy.roCatRun.global.security.jwt.AuthTokensGenerator;
import com.ssafy.roCatRun.global.security.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class GoogleService {
    private final MemberRepository memberRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthTokensGenerator authTokensGenerator;
    private final RefreshTokenRedisRepository refreshTokenRedisRepository;

    @Value("${oauth2.google.client_id}")
    private String clientId;

    @Value("${oauth2.google.client_secret}")
    private String clientSecret;

    @Value("${oauth2.google.redirect_uri}")
    private String defaultRedirectUri;

    private static final long TOKEN_EXPIRATION_TIME_MS = 1000 * 60 * 60 * 24 * 14;

    private String selectRedirectUri(String currentDomain) {
        if (currentDomain.contains("localhost")) {
            return defaultRedirectUri;
        } else {
            return defaultRedirectUri.replace("localhost:8080", currentDomain);
        }
    }

    public LoginResponse googleLogin(String code, String currentDomain) {
        log.info("인가 코드: {}", code);

        String redirectUri = selectRedirectUri(currentDomain);
        // 구글 토큰 정보 받아오기
        GoogleTokenInfo googleTokenInfo = getGoogleTokens(code, redirectUri);
        log.info("구글 액세스 토큰: {}", googleTokenInfo.accessToken);

        // 구글 사용자 정보 조회
        HashMap<String, Object> userInfo = getGoogleUserInfo(googleTokenInfo.accessToken);
        log.info("구글 사용자 정보: {}", userInfo);

        return processGoogleLogin(userInfo, googleTokenInfo);
    }

    public AuthTokens refreshGoogleToken(String refreshToken) {
        log.info("=================== Token Refresh Start ===================");

        String userId = jwtTokenProvider.extractSubject(refreshToken);
        String googleRefreshToken = refreshTokenRedisRepository.findByKey("GOOGLE_" + userId)
                .orElseThrow(() -> new TokenRefreshException("저장된 구글 리프레시 토큰이 없습니다."));

        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-type", "application/x-www-form-urlencoded;charset=utf-8");

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("refresh_token", googleRefreshToken);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        RestTemplate rt = new RestTemplate();
        ResponseEntity<String> response = rt.exchange(
                "https://oauth2.googleapis.com/token",
                HttpMethod.POST,
                request,
                String.class
        );

        JsonNode jsonNode = parseJsonResponse(response.getBody());
        String newGoogleAccessToken = jsonNode.get("access_token").asText();
        String newGoogleRefreshToken = jsonNode.has("refresh_token")
                ? jsonNode.get("refresh_token").asText()
                : googleRefreshToken;

        AuthTokens newJwtTokens = authTokensGenerator.generate(userId);
        log.info("Generated new JWT tokens: Access={}, Refresh={}",
                newJwtTokens.getAccessToken(),
                newJwtTokens.getRefreshToken()
        );

        if (!googleRefreshToken.equals(newGoogleRefreshToken)) {
            refreshTokenRedisRepository.save("GOOGLE_" + userId, newGoogleRefreshToken, TOKEN_EXPIRATION_TIME_MS);
        }
        refreshTokenRedisRepository.save(userId, newJwtTokens.getRefreshToken(), TOKEN_EXPIRATION_TIME_MS);

        return newJwtTokens;
    }

    private static class GoogleTokenInfo {
        String accessToken;
        String refreshToken;

        GoogleTokenInfo(String accessToken, String refreshToken) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
        }
    }

    private GoogleTokenInfo getGoogleTokens(String code, String redirectUri) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-type", "application/x-www-form-urlencoded;charset=utf-8");

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("redirect_uri", redirectUri);
        body.add("code", code);

        HttpEntity<MultiValueMap<String, String>> googleTokenRequest = new HttpEntity<>(body, headers);
        RestTemplate rt = new RestTemplate();
        ResponseEntity<String> response = rt.exchange(
                "https://oauth2.googleapis.com/token",
                HttpMethod.POST,
                googleTokenRequest,
                String.class
        );

        JsonNode jsonNode = parseJsonResponse(response.getBody());
        return new GoogleTokenInfo(
                jsonNode.get("access_token").asText(),
                jsonNode.get("refresh_token").asText()
        );
    }

    private HashMap<String, Object> getGoogleUserInfo(String accessToken) {
        HashMap<String, Object> userInfo = new HashMap<>();

        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + accessToken);

        HttpEntity<MultiValueMap<String, String>> googleUserInfoRequest = new HttpEntity<>(headers);
        RestTemplate rt = new RestTemplate();
        ResponseEntity<String> response = rt.exchange(
                "https://www.googleapis.com/oauth2/v2/userinfo",
                HttpMethod.GET,
                googleUserInfoRequest,
                String.class
        );

        JsonNode jsonNode = parseJsonResponse(response.getBody());

        String id = jsonNode.get("id").asText();
        String name = jsonNode.get("name").asText();

        userInfo.put("id", id);
        userInfo.put("nickname", name);

        return userInfo;
    }

    private JsonNode parseJsonResponse(String response) {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            return objectMapper.readTree(response);
        } catch (JsonProcessingException e) {
            log.error("JSON 파싱 실패", e);
            throw new RuntimeException("JSON 파싱 실패", e);
        }
    }

    private LoginResponse processGoogleLogin(HashMap<String, Object> userInfo, GoogleTokenInfo googleTokenInfo) {
        // socialId 타입 통일
        String socialId = String.valueOf(userInfo.get("id"));
        String nickname = userInfo.get("nickname").toString();

        Member member = memberRepository.findBySocialIdAndLoginType(socialId, "GOOGLE")
                .orElseGet(() -> {
                    Member newMember = Member.createMember(null, nickname, "GOOGLE", socialId);
                    return memberRepository.save(newMember);
                });

        AuthTokens jwtTokens = authTokensGenerator.generate(member.getId().toString());
        log.info("Generated JWT tokens for user {}: Access={}, Refresh={}",
                member.getId(),
                jwtTokens.getAccessToken(),
                jwtTokens.getRefreshToken()
        );

        refreshTokenRedisRepository.save(
                member.getId().toString(),
                jwtTokens.getRefreshToken(),
                TOKEN_EXPIRATION_TIME_MS
        );
        refreshTokenRedisRepository.save(
                "GOOGLE_" + member.getId().toString(),
                googleTokenInfo.refreshToken,
                TOKEN_EXPIRATION_TIME_MS
        );

        return new LoginResponse(jwtTokens);
    }
}