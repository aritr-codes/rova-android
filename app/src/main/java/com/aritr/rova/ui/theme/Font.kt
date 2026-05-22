package com.aritr.rova.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.aritr.rova.R

/**
 * Inter — the v1.0.0 record-screen typeface, per the
 * `mockups/new_uiux/01-record-home.html` reference pages. Delivered as a downloadable
 * Google Fonts family (no bundled .ttf, no APK cost). Weights 300/400/500/600
 * are every weight the mockup CSS uses; FontFamily.SansSerif (Roboto) is the
 * platform fallback while the first cold-launch fetch resolves.
 */
private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val interGoogleFont = GoogleFont("Inter")

val Inter: FontFamily = FontFamily(
    Font(googleFont = interGoogleFont, fontProvider = provider, weight = FontWeight.Light),    // 300
    Font(googleFont = interGoogleFont, fontProvider = provider, weight = FontWeight.Normal),   // 400
    Font(googleFont = interGoogleFont, fontProvider = provider, weight = FontWeight.Medium),   // 500
    Font(googleFont = interGoogleFont, fontProvider = provider, weight = FontWeight.SemiBold), // 600
)
