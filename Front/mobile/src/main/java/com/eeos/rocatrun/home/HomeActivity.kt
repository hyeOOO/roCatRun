package com.eeos.rocatrun.home

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.eeos.rocatrun.home.api.HomeViewModel
import com.eeos.rocatrun.login.data.TokenStorage
import com.eeos.rocatrun.socket.SocketHandler
import com.eeos.rocatrun.ui.theme.RoCatRunTheme

class HomeActivity : ComponentActivity() {
    private val token = TokenStorage.getAccessToken(this)
    private val homeViewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

//        homeViewModel.fetchHomeInfo(token)
        homeViewModel.fetchHomeInfo()

        setContent {

            // 소켓 초기화, 연결
            SocketHandler.initialize()
            SocketHandler.connect()

            RoCatRunTheme {
                HomeScreen(homeViewModel = homeViewModel)
            }
        }
    }
}
