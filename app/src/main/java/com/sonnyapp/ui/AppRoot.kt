package com.sonnyapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

enum class AppMode { MENU, REMOTE, GALLERY }

/**
 * 顶层导航（只有三个页面，不需要导航库）。
 *
 * 从某个模式返回菜单时**必须断开相机连接** ——
 * 相机一次只能跑一种模式（遥控 或 DLNA），留着旧连接会干扰新模式。
 * 断开动作在各页面自己的 onBack 里做（它们才持有 ViewModel）。
 */
@Composable
fun AppRoot() {
    var mode by remember { mutableStateOf(AppMode.MENU) }
    when (mode) {
        AppMode.MENU -> MenuScreen(
            onRemote = { mode = AppMode.REMOTE },
            onGallery = { mode = AppMode.GALLERY },
        )
        AppMode.REMOTE -> CameraScreen(onBack = { mode = AppMode.MENU })
        AppMode.GALLERY -> GalleryScreen(onBack = { mode = AppMode.MENU })
    }
}
