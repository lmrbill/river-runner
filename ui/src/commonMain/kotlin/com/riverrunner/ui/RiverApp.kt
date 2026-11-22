package com.riverrunner.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** The current screen plus the overlays shown above every screen (achievement toast, what's new). */
@Composable
fun BoxScope.RiverScreens(vm: AppViewModel) {
    when (val s = vm.screen) {
        Screen.Home -> HomeScreen(vm)
        Screen.Table -> TableScreen(vm)
        is Screen.Result -> ResultScreen(vm, s.summary)
        Screen.History -> HistoryScreen(vm)
        is Screen.Replay -> ReplayScreen(vm, s.record)
        Screen.Stats -> StatsScreen(vm)
        Screen.Settings -> SettingsScreen(vm)
        Screen.Guide -> GuideScreen(vm)
        Screen.Saves -> SavesScreen(vm)
        Screen.Achievements -> AchievementsScreen(vm)
        Screen.Setup -> SetupScreen(vm)
    }
    UnlockToast(vm.unlockToast, Modifier.align(Alignment.TopCenter))
    vm.whatsNew?.let { shown ->
        WhatsNewDialog(
            shown,
            onDismiss = vm::dismissWhatsNew,
            onShowAll = if (shown.size < vm.changelog.size) vm::showChangelog else null,
        )
    }
}
