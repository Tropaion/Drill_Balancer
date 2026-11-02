package dev.drillbalancer.ui.update

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.drillbalancer.ui.components.MessageBanner
import dev.drillbalancer.ui.components.UiMessage

/** An update notice, from its offer to a failed download. */
@Composable
fun UpdateBanner(
    notice: UpdateNotice,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onInstall: () -> Unit = {},
) {
    val name = "${notice.kind.label} ${notice.version}"
    when (val stage = notice.stage) {
        UpdateStage.Available -> MessageBanner(
            UiMessage("$name available", UiMessage.Kind.INFO), modifier, onDismiss, actionLabel = "Download", onAction = onDownload,
        )
        is UpdateStage.Downloading -> MessageBanner(
            UiMessage("Downloading ${name.replaceFirstChar(Char::lowercase)}: ${stage.percent} %", UiMessage.Kind.INFO), modifier,
        )
        UpdateStage.Ready -> MessageBanner(
            UiMessage("$name downloaded", UiMessage.Kind.SUCCESS), modifier, onDismiss, actionLabel = "Install", onAction = onInstall,
        )
        is UpdateStage.Failed -> MessageBanner(UiMessage(stage.message), modifier, onDismiss, actionLabel = "Retry", onAction = onDownload)
    }
}
