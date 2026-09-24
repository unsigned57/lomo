package com.lomo.data.repository

import com.lomo.data.engine.media.MediaPromotePlan

/**
 * Publication edge from a committed store command to the media location owner.
 *
 * A memo command receipt already carries every promoted artifact's final relative path and
 * witnessed digest; pushing those facts here lets the location owner update its display map
 * incrementally instead of re-walking the media manifest on the commit hot path.
 */
interface CommittedMediaLocationSink {
    fun publishCommittedMedia(plans: List<MediaPromotePlan>)
}
