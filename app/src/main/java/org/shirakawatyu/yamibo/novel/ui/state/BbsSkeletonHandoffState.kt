package org.shirakawatyu.yamibo.novel.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 只负责 Activity 首屏骨架与 BBS 页面内部骨架的交接协议。 */
class BbsSkeletonHandoffState {
    var isContainerMounted by mutableStateOf(false)
        private set

    var isLoadingCoverMounted by mutableStateOf(false)
        private set

    fun markContainerMounted() {
        isContainerMounted = true
    }

    fun markLoadingCoverMounted() {
        isLoadingCoverMounted = true
    }

    fun reset() {
        isContainerMounted = false
        isLoadingCoverMounted = false
    }

    fun isReady(
        hasSuccessfullyLoaded: Boolean,
        shouldDisplayLoadError: Boolean
    ): Boolean {
        return isContainerMounted &&
                (isLoadingCoverMounted || hasSuccessfullyLoaded || shouldDisplayLoadError)
    }
}
