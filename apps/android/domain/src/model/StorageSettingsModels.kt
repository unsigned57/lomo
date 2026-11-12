package com.lomo.domain.model

enum class StorageArea {
    ROOT,
    IMAGE,
    VOICE,
    SYNC_INBOX,
}

data class StorageAreaUpdate(val area: StorageArea, val location: StorageLocation?) {
    init {
        require(location != null || area == StorageArea.IMAGE || area == StorageArea.VOICE) {
            "Only media directories may be reset to unconfigured"
        }
    }
}
