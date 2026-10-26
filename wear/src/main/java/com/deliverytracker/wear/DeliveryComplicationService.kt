package com.deliverytracker.wear

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest

class DeliveryComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return createComplicationData(type, "ZOM", 14, 0.5f)
    }

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        val state = DeliveryDataRepository.getDeliveryState(this)

        if (!state.isActive) {
            listener.onComplicationData(createIdleComplicationData(request.complicationType))
            return
        }

        val data = createComplicationData(
            request.complicationType,
            state.brandName.take(3).uppercase(),
            state.remainingMinutes,
            state.progress
        )
        listener.onComplicationData(data ?: NoDataComplicationData())
    }

    private fun createIdleComplicationData(type: ComplicationType): ComplicationData {
        val text = PlainComplicationText.Builder("--").build()
        val title = PlainComplicationText.Builder("ETA").build()
        val contentDesc = PlainComplicationText.Builder("No active delivery orders").build()

        return when (type) {
            ComplicationType.RANGED_VALUE -> {
                RangedValueComplicationData.Builder(
                    value = 0f,
                    min = 0f,
                    max = 100f,
                    contentDescription = contentDesc
                )
                    .setText(text)
                    .setTitle(title)
                    .build()
            }
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = text,
                    contentDescription = contentDesc
                )
                    .setTitle(title)
                    .build()
            }
            else -> NoDataComplicationData()
        }
    }

    private fun createComplicationData(
        type: ComplicationType,
        brand: String,
        mins: Int,
        progress: Float
    ): ComplicationData? {
        val text = PlainComplicationText.Builder("${mins}m").build()
        val title = PlainComplicationText.Builder(brand).build()
        val contentDesc = PlainComplicationText.Builder("$brand delivery: $mins minutes remaining").build()

        return when (type) {
            ComplicationType.RANGED_VALUE -> {
                RangedValueComplicationData.Builder(
                    value = (progress * 100f).coerceIn(0f, 100f),
                    min = 0f,
                    max = 100f,
                    contentDescription = contentDesc
                )
                    .setText(text)
                    .setTitle(title)
                    .build()
            }
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = text,
                    contentDescription = contentDesc
                )
                    .setTitle(title)
                    .build()
            }
            else -> null
        }
    }
}
