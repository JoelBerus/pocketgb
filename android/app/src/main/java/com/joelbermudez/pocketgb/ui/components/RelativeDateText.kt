package com.joelbermudez.pocketgb.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.joelbermudez.pocketgb.R
import com.joelbermudez.pocketgb.library.RelativeDate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Texto de una fecha relativa («hace 3 h», «ayer», «5 oct»). */
@Composable
fun relativeDateText(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String =
    when (val date = RelativeDate.of(epochMs, nowMs)) {
        RelativeDate.Now -> stringResource(R.string.date_now)
        is RelativeDate.Minutes -> stringResource(R.string.date_minutes, date.count)
        is RelativeDate.Hours -> stringResource(R.string.date_hours, date.count)
        RelativeDate.Yesterday -> stringResource(R.string.date_yesterday)
        is RelativeDate.Days -> stringResource(R.string.date_days, date.count)
        is RelativeDate.Absolute ->
            SimpleDateFormat("d MMM", Locale.forLanguageTag("es")).format(Date(date.epochMs)).trimEnd('.')
    }
