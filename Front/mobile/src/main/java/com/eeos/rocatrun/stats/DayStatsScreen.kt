package com.eeos.rocatrun.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import com.eeos.rocatrun.R
import androidx.compose.foundation.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.Painter
import com.eeos.rocatrun.stats.api.Game
import com.eeos.rocatrun.stats.api.Player
import com.eeos.rocatrun.ui.components.StrokedText


@Composable
fun DayStatsScreen(games: List<Game>) {
    // 세부 모달 변수
//    var showDetail by remember { mutableStateOf(false) }

    // 세부 모달을 위한 상태: 클릭한 게임의 데이터를 저장
    var selectedGame by remember { mutableStateOf<Game?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        if (games.isEmpty()) {
            Text("게임 데이터가 없습니다.")
        } else {
            games.forEach { game ->
                DayStatCard(
                    date = game.date,
                    status = if (game.result) "정복완료" else "정복실패",
                    players = game.players.map { player ->
                        Player(
                            rank = player.rank,
                            profileUrl = player.profileUrl,
                            nickname = player.nickname,
                            distance = player.distance,
                            attackCount = player.attackCount
                        )
                    },
                    isSuccess = game.result,
                    bossImg = painterResource(id = R.drawable.all_img_boss1),  // 필요 시 수정
                    onClick = { selectedGame = game }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // 세부 모달 표시
//    if (showDetail) {
//        DetailDialog(onDismiss = { showDetail = false })
//    }

    // 세부 모달 표시: selectedGame이 null이 아닐 때만 보여줌
    selectedGame?.let { game ->
        DetailDialog(
            date = game.date,
            details = game.details,  // game.details 전달
            onDismiss = { selectedGame = null }
        )
    }
}

@Composable
fun DayStatCard(
    date: String,
    status: String,
    players: List<Player>,
    isSuccess: Boolean,
    bossImg: Painter,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clickable(onClick = onClick)
    ) {
        // 배경 이미지
        Image(
            painter = painterResource(id = R.drawable.stats_bg_day),
            contentDescription = "Card Background",
            contentScale = ContentScale.FillWidth,
            alpha = 0.8f,
            modifier = Modifier.fillMaxWidth()
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Transparent
            )
        ) {
            Column(
                modifier = Modifier.padding(vertical = 30.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.Start
            ) {
                // 날짜 및 인원수
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StrokedText(
                        text = date,
                        color = Color.White,
                        strokeColor = Color.Black,
                        fontSize = 25,
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StrokedText(
                            text = "${players.size}인",
                            color = Color.White,
                            strokeColor = Color.Black,
                            fontSize = 25,
                        )

                        val imageRes = if (players.size == 1) {
                            R.drawable.stats_img_person
                        } else {
                            R.drawable.stats_img_people
                        }

                        Image(
                            painter = painterResource(id = imageRes),
                            contentDescription = "Boss Img",
                            modifier = Modifier
                                .size(25.dp)
                                .offset(x = 5.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // 게임 결과
                Row(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Image(
                        painter = bossImg,
                        contentDescription = "Boss Img",
                        modifier = Modifier.size(35.dp)
                    )

                    StrokedText(
                        text = status,
                        color = if (isSuccess) Color(0xFF36DBEB) else Color(0xFFA3A1A5),
                        strokeColor = Color.Black,
                        fontSize = 34,
                        modifier = Modifier.offset(x = 15.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                // 플레이어 정보
                StrokedText(
                    text = "플레이어",
                    color = Color.White,
                    strokeColor = Color.Black,
                    fontSize = 20,
                )
                Spacer(modifier = Modifier.height(12.dp))

                players.forEachIndexed { index, player ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x38FFFFFF), shape = RoundedCornerShape(10.dp))
                            .padding(top = 8.dp, bottom = 8.dp, start = 4.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val rankImage = when (player.rank) {
                            1 -> R.drawable.stats_img_first
                            2 -> R.drawable.stats_img_second
                            3 -> R.drawable.stats_img_third
                            else -> null
                        }

                        if (rankImage != null) {
                            Image(
                                painter = painterResource(id = rankImage),
                                contentDescription = "${player.rank}등 이미지",
                                modifier = Modifier.size(35.dp)
                            )
                        } else {
                            Spacer(modifier = Modifier.size(12.dp))
                            Text(
                                text = "-",
                                color = Color.White,
                                fontSize = 14.sp,
                            )
                            Spacer(modifier = Modifier.size(12.dp))
                        }

                        Image(
                            painter = painterResource(id = R.drawable.stats_img_profile),
                            contentDescription = "Profile Img",
                            modifier = Modifier
                                .size(35.dp)
                                .weight(0.5f)
                        )
                        Text(
                            text = player.nickname,
                            color = Color.White,
                            fontSize = 18.sp,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.weight(1.5f)
                        )
                        Text(
                            text = player.distance,
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Image(
                            painter = painterResource(id = R.drawable.stats_img_can),
                            contentDescription = "Can Img",
                            modifier = Modifier
                                .size(25.dp)
                                .weight(0.3f)
                        )
                        Text(
                            text = "x",
                            color = Color.White,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(0.2f)
                        )
                        Text(
                            text = player.attackCount,
                            color = Color.White,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(0.2f)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}
