package com.locket.app.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.locket.app.ui.LocketViewModel

/**
 * Home giong app goc: VerticalPager 2 trang.
 * Trang 0 = moment moi nhat (full-bleed, caption de len anh, ten ban + thoi gian).
 * Trang 1 = lich su / Ky niem (lich thang, danh sach moment cu).
 * Khong doi navigation hien tai: cac nut trong 2 trang van dung onNavigate nhu cu.
 */
@Composable
fun HomePagerScreen(
    viewModel: LocketViewModel,
    initialMomentIndex: Int = 0,
    onNavigate: (String) -> Unit,
    onOpenFriends: () -> Unit,
    onOpenProfile: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        when (page) {
            0 -> HomeMomentScreen(
                viewModel = viewModel,
                initialMomentIndex = initialMomentIndex,
                onNavigate = onNavigate,
                onOpenFriends = onOpenFriends,
                onOpenProfile = onOpenProfile
            )
            else -> HistoryScreen(
                viewModel = viewModel,
                onNavigate = onNavigate,
                onOpenProfile = onOpenProfile
            )
        }
    }
}
