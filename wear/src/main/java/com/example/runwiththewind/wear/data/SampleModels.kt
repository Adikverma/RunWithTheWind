package com.example.runwiththewind.wear.data

import kotlinx.serialization.Serializable

@Serializable
data class Header(
    val v: Int = 1,
    val id: String,
    val sport: String = "RUNNING",
    val start: Long
)

@Serializable
data class Sample(
    val t: Long,                 // epoch ms
    val lat: Double? = null,
    val lon: Double? = null,
    val alt: Double? = null,
    val hr: Int? = null,
    val dist: Double? = null,
    val spd: Float? = null
)
