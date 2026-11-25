package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Self-contained Diagnostics card extension for uploading diagnostic reports to Cloudflare R2
 * through a user-owned Worker endpoint.
 */
internal object DiagnosticR2UploadUi {
    private const val VALUE_SEPARATOR = " · "

    @Volatile
    private var uploadInProgress = false
    private var uploadButtonRef: WeakReference<Button>? = null
    private var configButtonRef: WeakReference<Button>? = null

    fun attach(
        activity: Activity,
        card: LinearLayout,
        createButton: (String, () -> Unit) -> Button,
        buttonLayout: () -> LinearLayout.LayoutParams,
        reportFileName: () -> String,
        reportText: (Context) -> String,
        fallbackSaveLocal: () -> Unit,
    ) {
        val uploadButton = createButton(uploadButtonLabel(activity)) {
            val config = DiagnosticR2Uploader.loadConfig(activity)
            if (!config.isConfigured) {
                showConfigDialog(
                    activity = activity,
                    uploadAfterSave = true,
                    reportFileName = reportFileName,
                    reportText = reportText,
                    fallbackSaveLocal = fallbackSaveLocal,
                )
            } else {
                startUpload(activity, reportFileName, reportText, fallbackSaveLocal)
            }
        }.apply {
            isEnabled = !uploadInProgress
            setOnLongClickListener {
                showConfigDialog(
                    activity = activity,
                    uploadAfterSave = false,
                    reportFileName = reportFileName,
                    reportText = reportText,
                    fallbackSaveLocal = fallbackSaveLocal,
                )
                true
            }
        }
        uploadButtonRef = WeakReference(uploadButton)
        card.addView(uploadButton, buttonLayout())

        val configButton = createButton(configButtonLabel(activity)) {
            showConfigDialog(
                activity = activity,
                uploadAfterSave = false,
                reportFileName = reportFileName,
                reportText = reportText,
                fallbackSaveLocal = fallbackSaveLocal,
            )
        }
        configButtonRef = WeakReference(configButton)
        card.addView(configButton, buttonLayout())
    }

    private fun uploadButtonLabel(context: Context): String =
        context.getString(if (uploadInProgress) R.string.r2_upload_in_progress else R.string.r2_upload_report)

    private fun configButtonLabel(context: Context): String {
        val config = DiagnosticR2Uploader.loadConfig(context)
        val status = when {
            !config.isConfigured -> context.getString(R.string.r2_upload_status_not_configured)
            config.fromBuildDefaults -> context.getString(R.string.r2_upload_status_default)
            else -> context.getString(R.string.r2_upload_status_configured)
        }
        return "${context.getString(R.string.r2_upload_config_title)}$VALUE_SEPARATOR$status"
    }

    private fun refreshButtons(context: Context) {
        uploadButtonRef?.get()?.apply {
            isEnabled = !uploadInProgress
            text = uploadButtonLabel(context)
        }
        configButtonRef?.get()?.apply {
            text = configButtonLabel(context)
        }
    }

    private fun showConfigDialog(
        activity: Activity,
        uploadAfterSave: Boolean,
        reportFileName: () -> String,
        reportText: (Context) -> String,
        fallbackSaveLocal: () -> Unit,
    ) {
        val dialogContext = activity.appDialogContext()
        val density = dialogContext.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()

        val current = DiagnosticR2Uploader.loadConfig(activity)
        val container = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(8))
        }

        val hintText = TextView(dialogContext).apply {
            text = activity.getString(R.string.r2_upload_config_description)
            textSize = 14f
            setPadding(0, 0, 0, dp(12))
        }
        container.addView(hintText)

        val endpointInput = EditText(dialogContext).apply {
            hint = activity.getString(R.string.r2_upload_endpoint_hint)
            setText(current.endpoint)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        container.addView(
            endpointInput,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val tokenInput = EditText(dialogContext).apply {
            hint = activity.getString(R.string.r2_upload_token_hint)
            setText(current.token)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        container.addView(
            tokenInput,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            },
        )

        val builder = activity.appDialogBuilder()
            .setTitle(activity.getString(R.string.r2_upload_config_title))
            .setView(container)
            .setPositiveButton(
                activity.getString(if (uploadAfterSave) R.string.r2_upload_save_and_upload else R.string.r2_upload_save),
                null,
            )
            .setNegativeButton(activity.getString(R.string.cancel), null)

        if (!current.fromBuildDefaults && DiagnosticR2Uploader.hasBuildDefaults()) {
            builder.setNeutralButton(activity.getString(R.string.r2_upload_use_default)) { _, _ ->
                DiagnosticR2Uploader.clearOverride(activity)
                refreshButtons(activity)
            }
        }

        val dialog = builder.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val rawEndpoint = endpointInput.text.toString().trim()
            val rawToken = tokenInput.text.toString().trim()
            if (rawEndpoint.isEmpty()) {
                if (uploadAfterSave) {
                    endpointInput.error = activity.getString(R.string.r2_upload_invalid_endpoint)
                    return@setOnClickListener
                }
                DiagnosticR2Uploader.saveOverride(activity, "", "")
                refreshButtons(activity)
                dialog.dismiss()
                return@setOnClickListener
            }

            val normalized = DiagnosticR2Uploader.normalizeEndpoint(rawEndpoint)
            if (normalized == null) {
                endpointInput.error = activity.getString(R.string.r2_upload_invalid_endpoint)
                return@setOnClickListener
            }

            DiagnosticR2Uploader.saveOverride(activity, normalized, rawToken)
            refreshButtons(activity)
            dialog.dismiss()
            if (uploadAfterSave) {
                startUpload(activity, reportFileName, reportText, fallbackSaveLocal)
            }
        }
    }

    private fun startUpload(
        activity: Activity,
        reportFileName: () -> String,
        reportText: (Context) -> String,
        fallbackSaveLocal: () -> Unit,
    ) {
        if (uploadInProgress) return
        uploadInProgress = true
        refreshButtons(activity)

        val appContext = activity.applicationContext
        val fileName = reportFileName()
        Thread({
            val outcome = runCatching {
                val report = reportText(appContext)
                DiagnosticR2Uploader.upload(appContext, fileName, report)
            }
            activity.runOnUiThread {
                uploadInProgress = false
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                refreshButtons(activity)

                if (outcome.isSuccess) {
                    val result = outcome.getOrThrow()
                    activity.appDialogBuilder()
                        .setTitle(activity.getString(R.string.r2_upload_success_title))
                        .setMessage(
                            activity.getString(
                                R.string.r2_upload_success_message,
                                result.objectKey,
                                formatBytes(result.compressedBytes),
                                formatBytes(result.uncompressedBytes),
                            ),
                        )
                        .setPositiveButton(activity.getString(R.string.done), null)
                        .setNeutralButton(activity.getString(R.string.r2_upload_config_title)) { _, _ ->
                            showConfigDialog(activity, false, reportFileName, reportText, fallbackSaveLocal)
                        }
                        .show()
                } else {
                    val error = outcome.exceptionOrNull()
                    activity.appDialogBuilder()
                        .setTitle(activity.getString(R.string.r2_upload_failed_title))
                        .setMessage(formatErrorMessage(activity, error))
                        .setPositiveButton(activity.getString(R.string.r2_upload_retry)) { _, _ ->
                            startUpload(activity, reportFileName, reportText, fallbackSaveLocal)
                        }
                        .setNeutralButton(activity.getString(R.string.r2_upload_save_local)) { _, _ ->
                            fallbackSaveLocal()
                        }
                        .setNegativeButton(activity.getString(R.string.close), null)
                        .show()
                }
            }
        }, "diplay-r2-upload").start()
    }

    private fun formatErrorMessage(context: Context, error: Throwable?): String {
        val uploadError = error as? DiagnosticR2Uploader.UploadException
            ?: return context.getString(R.string.r2_upload_error_generic, error?.message ?: "unknown")
        return when (uploadError.kind) {
            DiagnosticR2Uploader.ErrorKind.NOT_CONFIGURED ->
                context.getString(R.string.r2_upload_error_not_configured)
            DiagnosticR2Uploader.ErrorKind.INVALID_ENDPOINT ->
                context.getString(R.string.r2_upload_invalid_endpoint)
            DiagnosticR2Uploader.ErrorKind.NO_INTERNET ->
                context.getString(R.string.r2_upload_error_no_internet)
            DiagnosticR2Uploader.ErrorKind.UNAUTHORIZED ->
                context.getString(R.string.r2_upload_error_unauthorized, uploadError.httpStatus ?: 401)
            DiagnosticR2Uploader.ErrorKind.HTTP_ERROR ->
                context.getString(R.string.r2_upload_error_http, uploadError.message ?: "HTTP error")
            DiagnosticR2Uploader.ErrorKind.NETWORK_ERROR ->
                context.getString(R.string.r2_upload_error_network, uploadError.message ?: "network error")
        }
    }

    private fun formatBytes(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }
}
