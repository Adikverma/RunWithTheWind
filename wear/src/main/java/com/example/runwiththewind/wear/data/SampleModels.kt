package com.example.runwiththewind.wear.data

import kotlinx.serialization.Serializable

@Serializable
data class Header(
    val v: Int = 1,
    val id: String,
    val sport: String = "RUN",
    val start: Long
)

@Serializable
data class Sample(
    val t: Long,               // epoch ms
    val lat: Double? = null,
    val lon: Double? = null,
    val alt: Double? = null,   // metres
    val hr: Int? = null,       // bpm
    val dist: Double? = null,  // cumulative metres
    val spd: Float? = null,    // m/s
    val mv: Long? = null,      // cumulative moving time, ms
    val eg: Double? = null     // cumulative elevation gain, metres
)
