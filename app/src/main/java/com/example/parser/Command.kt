package com.example.parser

enum class VolumeAction {
    UP,
    DOWN,
    MUTE,
    UNMUTE,
    SET_PERCENT
}

enum class BrightnessAction {
    UP,
    DOWN,
    SET_PERCENT
}

enum class DeviceQueryType {
    TIME,
    DATE,
    BATTERY
}

enum class MediaAction {
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS
}

enum class SystemPanelType {
    WIFI,
    BLUETOOTH,
    DO_NOT_DISTURB
}

/**
 * Complete offline voice command hierarchy for NEMI.
 */
sealed class Command {
    data class Call(val name: String) : Command()
    data class Torch(val on: Boolean) : Command()
    data class Alarm(val hour24: Int, val minute: Int) : Command()
    data class OpenApp(val appQuery: String) : Command()
    data class SetTimer(val durationSeconds: Int) : Command()
    data class Volume(val action: VolumeAction, val percent: Int? = null) : Command()
    data class Brightness(val action: BrightnessAction, val percent: Int? = null) : Command()
    data class DeviceQuery(val queryType: DeviceQueryType) : Command()
    data class Media(val mediaAction: MediaAction) : Command()
    data class SystemPanel(val panelType: SystemPanelType) : Command()
    data class PickOption(val indexOneBased: Int) : Command()
    data class Unknown(val text: String) : Command()

    /**
     * Privacy-safe summary string that never logs contact names or phone numbers.
     */
    fun toSummaryString(redactContactName: Boolean = false): String = when (this) {
        is Call -> if (redactContactName) "Call(<redacted-contact>)" else "Call(name=\"$name\")"
        is Torch -> "Torch(on=$on)"
        is Alarm -> String.format("Alarm(%02d:%02d)", hour24, minute)
        is OpenApp -> "OpenApp(\"$appQuery\")"
        is SetTimer -> "SetTimer(${durationSeconds}s)"
        is Volume -> if (percent != null) "Volume($action, $percent%)" else "Volume($action)"
        is Brightness -> if (percent != null) "Brightness($action, $percent%)" else "Brightness($action)"
        is DeviceQuery -> "DeviceQuery($queryType)"
        is Media -> "Media($mediaAction)"
        is SystemPanel -> "SystemPanel($panelType)"
        is PickOption -> "PickOption($indexOneBased)"
        is Unknown -> "Unknown(\"$text\")"
    }
}
