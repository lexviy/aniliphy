// Adapted from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0.
// In androidx.preference so it can reach two package-private hooks the library's own adapter uses.
@file:Suppress("PackageDirectoryMismatch")

package androidx.preference

/** The package-private [EditTextPreference.getOnBindEditTextListener]. */
internal fun EditTextPreference.bindListener(): EditTextPreference.OnBindEditTextListener? = onBindEditTextListener

/** Calls [onChange] whenever this preference's state, visibility or children change, as the library redraws them. */
internal fun Preference.setOnStateChangeListener(onChange: (Preference) -> Unit) {
    setOnPreferenceChangeInternalListener(
        object : Preference.OnPreferenceChangeInternalListener {
            override fun onPreferenceChange(preference: Preference) = onChange(preference)
            override fun onPreferenceHierarchyChange(preference: Preference) = onChange(preference)
            override fun onPreferenceVisibilityChange(preference: Preference) = onChange(preference)
        },
    )
}
