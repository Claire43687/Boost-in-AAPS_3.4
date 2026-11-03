package app.aaps.plugins.aps.keys

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.ApsStrings

enum class ApsIntentKey(
    override val key: String,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val preferenceType: PreferenceType = PreferenceType.URL,
    // urlRef rather than urlResId: a resource id is an Android Int and means nothing off Android.
    override val urlRef: TextRef? = null,
    override val exportable: Boolean = false
) : IntentPreferenceKey {

    LinkToDocs(
        key = "aps_link_to_docs",
        title = ApsStrings.openapsama_link_to_preference_json_doc_txt,
        preferenceType = PreferenceType.URL,
        urlRef = ApsStrings.openapsama_link_to_preference_json_doc
    ),

    // Boost: opens the Health Connect permission screen. The click is attached at runtime by the
    // Boost plugin (withClick), since starting an activity needs an Android Context.
    GrantHealthConnect(
        key = "boost_hc_grant_permission",
        title = ApsStrings.boost_hc_grant_title,
        summary = ApsStrings.boost_hc_grant_summary,
        preferenceType = PreferenceType.ACTIVITY
    )
}
