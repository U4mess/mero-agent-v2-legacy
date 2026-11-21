package com.u4mess.ume.service

import com.u4mess.ume.sensors.ScreenReaderAccessibilityService

/**
 * A transparent accessibility overlay that makes the island tappable while it sits
 * inside the status bar. Regular application overlays are below SystemUI there.
 */
object IslandTouchRelay {

    private var service: ScreenReaderAccessibilityService? = null
    private var visible = false
    private var offsetDp = 7
    private var widthDp = 110
    private var heightDp = 28
    private var onClick: (() -> Unit)? = null

    fun attach(service: ScreenReaderAccessibilityService) {
        this.service = service
        apply()
    }

    fun detach(service: ScreenReaderAccessibilityService) {
        if (this.service === service) this.service = null
    }

    fun update(
        visible: Boolean,
        offsetDp: Int,
        widthDp: Int,
        heightDp: Int,
        onClick: (() -> Unit)?,
    ) {
        this.visible = visible
        this.offsetDp = offsetDp
        this.widthDp = widthDp
        this.heightDp = heightDp
        this.onClick = onClick
        apply()
    }

    fun hide() {
        visible = false
        apply()
    }

    private fun apply() {
        service?.setIslandTouchRelay(visible, offsetDp, widthDp, heightDp, onClick)
    }
}
