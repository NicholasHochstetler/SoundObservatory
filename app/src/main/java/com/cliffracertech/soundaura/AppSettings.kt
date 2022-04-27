/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura

import android.Manifest
import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable fun AppSettings(
    contentPadding: PaddingValues
) = Surface(
    color = MaterialTheme.colors.background,
    modifier = Modifier.fillMaxSize(1f)
) {
    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { DisplaySettingsCategory() }
        item { PlaybackSettingsCategory() }
        item { AboutSettingsCategory() }
    }
}

@Composable private fun DisplaySettingsCategory() {
    SettingCategory(
        title = stringResource(R.string.display_category_description),
        content = listOf @Composable {
            val viewModel: SettingsViewModel = viewModel()
            Setting(title = stringResource(R.string.app_theme_description)) {
                EnumRadioButtonGroup(
                    modifier = Modifier.padding(end = 16.dp),
                    values = AppTheme.values(),
                    valueNames = AppTheme.stringValues(),
                    currentValue = viewModel.appTheme,
                    onValueClick = viewModel::onAppThemeClick)
            }
        })
}

@Composable private fun PlaybackSettingsCategory() {
    val autoPauseDuringCallSetting = @Composable {
        val viewModel: SettingsViewModel = viewModel()
        Setting(
            title = stringResource(R.string.auto_pause_during_calls_setting_title),
            subtitle = stringResource(R.string.auto_pause_during_calls_setting_subtitle),
            onClick = { viewModel.onAutoPauseDuringCallClick() }
        ) {
            Switch(checked = viewModel.autoPauseDuringCall,
                onCheckedChange = { viewModel.onAutoPauseDuringCallClick() },
                colors = SwitchDefaults.colors(
                    uncheckedThumbColor = MaterialTheme.colors.background))
        }
        if (viewModel.showingAskForPhoneStatePermissionDialog) {
            val context = LocalContext.current
            val activity = context as? Activity
            val showExplanation = activity?.shouldShowRequestPermissionRationale(
                Manifest.permission.READ_PHONE_STATE) ?: true
            PhoneStatePermissionDialog(
                showExplanationFirst = showExplanation,
                onDismissRequest = viewModel::onAskForPhoneStatePermissionDialogDismiss,
                onPermissionResult = viewModel::onAskForPhoneStatePermissionDialogConfirm)
        }
    }
    val titleTutorialSetting = @Composable {
        DialogSetting(
            title = stringResource(R.string.control_playback_using_tile_setting_title),
            content = { TileTutorialDialog(onDismissRequest = it) })
    }
    SettingCategory(
        title = stringResource(R.string.playback_category_description),
        content = listOf(autoPauseDuringCallSetting, titleTutorialSetting))
}

@Composable private fun AboutSettingsCategory() {
    val title = stringResource(R.string.about_category_description)
    val privacyPolicySetting = @Composable {
        DialogSetting(stringResource(R.string.privacy_policy_description)) {
            PrivacyPolicyDialog(onDismissRequest = it)
        }
    }
    val openSourceLicensesSetting = @Composable {
        DialogSetting(stringResource(R.string.open_source_licenses_description)) {
            OpenSourceLibrariesUsedDialog(onDismissRequest = it)
        }
    }
    val aboutAppSetting = @Composable {
        DialogSetting(stringResource(R.string.about_app_description)) {
            AboutAppDialog(onDismissRequest = it)
        }
    }
    SettingCategory(title, listOf(
        privacyPolicySetting,
        openSourceLicensesSetting,
        aboutAppSetting))
}