package com.aniliphy.core

enum class TapZones(val label: String) { Off("Off"), Normal("Normal"), Inverted("Inverted") }

enum class AdvanceMode(val label: String) { Automatic("Automatic"), Ask("Ask first") }

enum class ReaderOrientation(val label: String) {
    Portrait("Portrait"),
    Landscape("Landscape"),
    FollowPhone("Follow phone"),
}
