package com.eeos.rocatrun.login

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.eeos.rocatrun.R
import com.eeos.rocatrun.login.LoginButton
import androidx.compose.ui.platform.LocalContext
import com.eeos.rocatrun.login.social.KakaoLoginHandler
// 프로필 이미지 갤러리에서 불러오기
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.ui.draw.clip
import coil3.ImageLoader
import androidx.compose.material3.TextFieldDefaults
import coil3.compose.rememberAsyncImagePainter
import coil3.gif.AnimatedImageDecoder
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.eeos.rocatrun.home.HomeActivity
import androidx.compose.material.AlertDialog


@Composable
fun LoginScreen(modifier: Modifier = Modifier) {
    var showDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current


        Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF051330))
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceAround,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 상단 제목 텍스트
        Text(
            text = "로캣냥",
            style = TextStyle(
                fontSize = 48.sp,
                fontWeight = FontWeight(400),
                color = Color(0xFFFFFFFF),
                fontFamily = FontFamily(Font(R.font.neodgm)),
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier
                .width(210.dp)
                .height(57.dp)
        )

        // 중앙 이미지 (지구 및 고양이)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(350.dp)
                .height(370.dp)
        ) {
            GifImage(

                modifier = Modifier.fillMaxSize(),
                gifResId = R.drawable.login_gif_earth

            )
            GifImage(
                modifier = Modifier
                    .width(136.dp)
                    .height(136.dp)
                    .offset(y = -(160).dp, x = 10.dp),
                gifResId = R.drawable.login_gif_whitecat
            )

        }

        // 로그인 버튼들
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LoginButton(
                text = "카카오 로그인",
                borderColor = Color(0xFFFFEB3C),
                backgroundColor = Color(0x4AFFEB3C),
                iconResId = R.drawable.login_icon_kakao,
                onClick = {
                    KakaoLoginHandler.performLogin(
                        context = context,
                        onSuccess = {code ->
                            Log.i("LoginScreen", "인가 코드 성공: $code")
                            showDialog = true
                        },
                        onError = { error ->
                            Log.e("LoginScreen", "카카오 로그인 오류", error)
                        }
                        )

                }
            )
            LoginButton(
                text = "네이버 로그인",
                borderColor = Color(0xFF00C73C),
                backgroundColor = Color(0x4A00C73C),
                iconResId = R.drawable.login_icon_naver,
                onClick = {
                    showDialog = true }
            )
            LoginButton(
                text = "구글 로그인",
                borderColor = Color(0xFFFFFFFF),
                backgroundColor = Color(0x4AFFFFFF),
                iconResId = R.drawable.login_icon_google,
                onClick = {
                    showDialog = true
                    }
            )
        }
    }

    // 모달 표시
    if (showDialog) {
        UserInfoDialog(
            onDismiss = { showDialog = false },
            onConfirm = { showDialog = false },
            profileImageResId = R.drawable.login_img_profile,  // 프로필 이미지 리소스
            borderImageResId = R.drawable.login_bg_greenmodal, // 모달 테두리 이미지 리소스
            okButtonImageResId = R.drawable.login_btn_ok,   // OK 버튼 이미지 리소스

        )
    }

}

@Composable
fun UserInfoDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    profileImageResId: Int,
    borderImageResId: Int,
    okButtonImageResId: Int
) {
    // 사용자 닉네임
    var nickname by remember { mutableStateOf("") }
    var showNicknameAlert by remember { mutableStateOf(false) } // 경고 알림 표시 여부
    var profileImageUri by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        profileImageUri = uri
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
        ) {
            Image(
                painter = painterResource(id = borderImageResId),
                contentDescription = "Dialog Border",
                modifier = Modifier
                    .width(700.dp)
                    .height(600.dp),
                contentScale = ContentScale.FillBounds
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "유저 정보 입력",
                    style = TextStyle(
                        fontSize = 35.sp,
                        color = Color.White,
                        fontFamily = FontFamily(Font(R.font.neodgm)),
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.offset(y = 50.dp)
                )

                Image(
                    painter = if (profileImageUri != null)
                        rememberAsyncImagePainter(model = profileImageUri)
                    else
                        painterResource(id = profileImageResId),
                    contentDescription = "Profile Image",
                    modifier = Modifier
                        .offset(y = 70.dp)
                        .size(100.dp)
                        .clip(CircleShape)
                        .border(2.dp, Color.White, CircleShape)
                        .clickable {
                            imagePickerLauncher.launch("image/*")
                        },
                    contentScale = ContentScale.Crop
                )

                Spacer(modifier = Modifier.height(80.dp))

                // 닉네임 입력 필드
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextField(
                        value = nickname,
                        onValueChange = { newNickname ->
                            if (newNickname.length <= 8) {
                                nickname = newNickname
                            } else {
                                showNicknameAlert = true // 경고 알림 표시
                            }
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 16.sp,
                            fontFamily = FontFamily(Font(R.font.neodgm))
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.Gray, RoundedCornerShape(8.dp)),
                        placeholder = {
                            Text(
                                text = "닉네임을 입력하세요",
                                color = Color.Gray,
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily(Font(R.font.neodgm))
                                )
                            )
                        }
                    )

                    // 중복 확인 버튼
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 10.dp)
                            .background(Color(0xFF00FFCC), shape = RoundedCornerShape(8.dp))
                            .padding(vertical = 8.dp, horizontal = 16.dp)
                            .clickable { /* 중복 확인 로직 */ },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "중복 확인",
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily(Font(R.font.neodgm))
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Image(
                    painter = painterResource(id = okButtonImageResId),
                    contentDescription = "OK Button",
                    modifier = Modifier
                        .width(150.dp)
                        .height(70.dp)
                        .offset(y = 40.dp)
                        .clickable {
                            val intent = Intent(context, HomeActivity::class.java)
                            context.startActivity(intent)
                        }
                )
            }
        }
    }

    // 닉네임 경고 알림 다이얼로그
    if (showNicknameAlert) {
        AlertDialog(
            onDismissRequest = { showNicknameAlert = false },
            confirmButton = {
                Text(
                    text = "확인",
                    modifier = Modifier
                        .clickable { showNicknameAlert = false }
                        .padding(8.dp),
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                )
            },
            text = {
                Text(
                    text = "닉네임은 최대 8글자까지 입력 가능합니다.",
                    style = TextStyle(fontSize = 16.sp, color = Color.Black)
                )
            }
        )
    }
}


@Composable
fun GifImage(modifier: Modifier = Modifier, gifResId: Int) {
    val context = LocalContext.current

    // Coil3의 GIF 디코더를 적용한 ImageLoader 생성
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                    add(AnimatedImageDecoder.Factory(enforceMinimumFrameDelay = true))

            }
            .build()
    }

    // GIF 이미지를 위한 ImageRequest 구성
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data(gifResId)
            .crossfade(true)
            .build(),
        imageLoader = imageLoader
    )

    // 이미지 디스플레이
    Image(
        painter = painter,
        contentDescription = "GIF Image",
        modifier = modifier,
        contentScale = ContentScale.Crop
    )
}

