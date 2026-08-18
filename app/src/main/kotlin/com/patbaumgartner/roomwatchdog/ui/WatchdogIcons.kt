package com.patbaumgartner.roomwatchdog.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.patbaumgartner.roomwatchdog.R

/**
 * The Material icons this app draws, vendored as vector drawables. `material-icons-extended` is
 * frozen at 1.7.8 while the rest of Compose moves on, and it shipped thousands of icons to use
 * seventeen. The drawables were generated from that library, so the geometry is unchanged.
 */
internal object WatchdogIcons {

    val ArrowBack: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_arrow_back)

    val DeleteOutline: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_delete_outline)

    val Edit: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_edit)

    val FiberManualRecord: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_fiber_manual_record)

    val FilterAlt: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_filter_alt)

    val FilterAltOff: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_filter_alt_off)

    val GraphicEq: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_graphic_eq)

    val Headphones: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_headphones)

    val MoreHoriz: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_more_horiz)

    val PersonOutline: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_person_outline)

    val PlayArrow: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_play_arrow)

    val Settings: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_settings)

    val Share: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_share)

    val Stop: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_stop)

    val Tune: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_tune)

    val VolumeOff: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_volume_off)

    val VolumeUp: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_volume_up)
}
