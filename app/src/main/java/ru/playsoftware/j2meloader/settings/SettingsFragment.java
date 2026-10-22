/*
 * Copyright 2017-2018 Nikita Shakarun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader.settings;

import static ru.playsoftware.j2meloader.util.Constants.PREF_EMULATOR_DIR;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Color;
import android.view.View;
import android.provider.Settings;
import android.widget.Toast;
import android.provider.DocumentsContract;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.preference.SwitchPreferenceCompat;

import java.io.File;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.config.ProfilesActivity;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.playsoftware.j2meloader.util.PickDirResultContract;

import static ru.playsoftware.j2meloader.util.Constants.PREF_ADD_CUTOUT_AREA;
import static ru.playsoftware.j2meloader.util.Constants.PREF_BACKGROUND_GAME_ENABLED;

public class SettingsFragment extends PreferenceFragmentCompat {
	private Preference prefFolder;
	private final ActivityResultLauncher<String> notificationsLauncher =
			registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
				if (granted) {
					SwitchPreferenceCompat enable = findPreference(PREF_BACKGROUND_GAME_ENABLED);
					if (enable != null) enable.setChecked(true);
				} else if (isAdded()) {
					Toast.makeText(requireContext(), R.string.bg_game_notification_permission,
							Toast.LENGTH_LONG).show();
				}
			});
	private final ActivityResultLauncher<String> openDirLauncher = registerForActivityResult(
			new PickDirResultContract(),
			this::onPickDirResult);

	@Override
	public void onCreatePreferences(Bundle bundle, String s) {
		addPreferencesFromResource(R.xml.preferences);
		findPreference("pref_default_settings").setIntent(new Intent(requireActivity(), ProfilesActivity.class));
		SwitchPreferenceCompat backgroundMode = findPreference(PREF_BACKGROUND_GAME_ENABLED);
		if (backgroundMode != null) {
			backgroundMode.setOnPreferenceChangeListener((pref, value) -> {
				// Starting a foreground service without a visible notification
				// would leave no obvious Stop control for the user.
				if (Boolean.TRUE.equals(value)
						&& Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
						&& ContextCompat.checkSelfPermission(requireContext(),
							Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
					notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
					return false;
				}
				return true;
			});
		}
		prefFolder = findPreference(PREF_EMULATOR_DIR);
		prefFolder.setSummary(Config.getEmulatorDir());
		prefFolder.setOnPreferenceClickListener(preference -> {
			if (FileUtils.isExternalStorageLegacy()) {
				openDirLauncher.launch(null);
			}
			return true;
		});
		Preference batterySettings = findPreference("pref_background_battery_settings");
		if (batterySettings != null) {
			batterySettings.setOnPreferenceClickListener(pref -> {
				Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
				try {
					startActivity(intent);
				} catch (ActivityNotFoundException e) {
					startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
						Uri.parse("package:" + requireContext().getPackageName())));
				}
				return true;
			});
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			findPreference(PREF_ADD_CUTOUT_AREA).setVisible(true);
		}
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		RecyclerView list = getListView();
		int gutter = Math.round(12 * getResources().getDisplayMetrics().density);
		list.setBackgroundColor(Color.TRANSPARENT);
		list.setClipToPadding(false);
		list.setPadding(gutter, 0, gutter, gutter);
	}

	private void openPicker() {
		try {
			startActivity(getFileManagerIntentOnDocumentProvider(Intent.ACTION_VIEW));
			return;
		} catch (ActivityNotFoundException ignored) {}

		try {
			startActivity(getFileManagerIntentOnDocumentProvider("android.provider.action.BROWSE"));
			return;
		} catch (ActivityNotFoundException ignored) {}

		try {
			// Just try to open the file manager, try the package name used on "normal" phones
			startActivity(getFileManagerIntent("com.google.android.documentsui"));
			return;
		} catch (ActivityNotFoundException ignored) {}

		try {
			// Next, try the AOSP package name
			startActivity(getFileManagerIntent("com.android.documentsui"));
		} catch (ActivityNotFoundException ignored) {}
	}

	private Intent getFileManagerIntent(String packageName) {
		// Fragile, but some phones don't expose the system file manager in any better way
		Intent intent = new Intent(Intent.ACTION_MAIN);
		intent.setClassName(packageName, "com.android.documentsui.files.FilesActivity");
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		return intent;
	}

	private Intent getFileManagerIntentOnDocumentProvider(String action) {
		String authority = requireContext().getPackageName() + ".documentProvider";
		String root = new File(Config.getEmulatorDir()).getAbsolutePath();
		Intent intent = new Intent(action);
		intent.addCategory(Intent.CATEGORY_DEFAULT);
		intent.setData(DocumentsContract.buildRootUri(authority, root));
		intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
				| Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
		return intent;
	}

	private void onPickDirResult(Uri uri) {
		if (uri == null) {
			return;
		}
		File file = new File(uri.getPath());
		String path = file.getAbsolutePath();
		if (!FileUtils.initWorkDir(file)) {
			new AlertDialog.Builder(requireActivity())
					.setTitle(R.string.error)
					.setCancelable(false)
					.setMessage(getString(R.string.create_apps_dir_failed, path))
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(R.string.choose, (d, w) -> openDirLauncher.launch(null))
					.show();
			return;
		}
		getPreferenceManager().getSharedPreferences().edit()
				.putString(PREF_EMULATOR_DIR, path)
				.apply();
		prefFolder.setSummary(path);
	}
}
